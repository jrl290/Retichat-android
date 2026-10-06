package com.newendian.retichat

/**
 * A photo leaves without where it was taken (James, 2026-10-05): a JPEG, PNG
 * or WebP, the formats a photo is sent in. Attachments go at their
 * original size (James, 2026-09-30), so nothing is decoded or re-encoded:
 * [withoutLocation] removes the metadata that can carry a location and keeps
 * every other byte as picked, the image data above all. What it does not
 * read, it does not keep: a part of a photo it cannot parse goes whole.
 *
 * A port of the web's Retichat-js/lib/photo_metadata.js, function for function
 * (same constants, same names, same order, so the two read side by side), held
 * to it by vectors the web's own function writes (scripts/photo_location_vectors.mjs,
 * PhotoMetadataTest): the two must give the same bytes for every input, the
 * malformed ones included, to the byte.
 *
 * JPEG (SOI, segments, scans, EOI):
 *  - EXIF (APP1 "Exif"): the GPS IFD goes. Its pointer (GPSInfo 0x8825) leaves
 *    every IFD that holds one, and so do XMP and IPTC put in the TIFF block
 *    (tags 0x02BC, 0x83BB, 0x8649); then every byte no IFD left references
 *    becomes zero, the GPS IFD and its values among them, in place: the block
 *    keeps its length, so every other offset in it still holds, and the rest
 *    is kept, Orientation (0x0112) above all, so a receiver that rotates by it
 *    shows the photo upright, as before. GPS a naive stripper left behind
 *    without its pointer goes the same way. The thumbnail (IFD1) is a JPEG of
 *    its own and is cleaned the same way, where it is. An EXIF block that
 *    cannot be parsed goes whole.
 *  - XMP (APP1 "http://ns.adobe.com/xap/1.0/" and its extension segments)
 *    goes, so does every other APP1: XMP repeats the GPS and adds place names.
 *  - IPTC (APP13, Photoshop) goes: City, Sub-location, Province/State, Country.
 *  - Every other APPn goes as well, but those a decoder needs to show the
 *    picture: APP0 JFIF, APP2 ICC_PROFILE, APP2 MPF, APP2 ISO 21496-1 (an HDR
 *    gain map's parameters) and APP14 Adobe. The others are vendor blobs this
 *    does not read, and some carry location (GoPro's APP6, a C2PA manifest).
 *  - After EOI, only the images the MPF index lists (an HDR gain map, a depth
 *    map) are kept, each cleaned as the photo is, and the index is rewritten to
 *    where they now are. Anything else there goes: a motion photo's video,
 *    Samsung's trailer (which names the network's country).
 *  The tables, the scans and COM segments are kept byte for byte. Bytes between
 *  segments that are no marker go, as a decoder skips them, and so do segments
 *  of a reserved marker, which no decoder reads.
 *
 * PNG (chunks): eXIf is cleaned as EXIF above, its CRC recomputed, and goes
 * whole when it cannot be parsed. Every tEXt, zTXt and iTXt whose keyword is
 * XMP's, an ImageMagick raw profile or names EXIF, XMP, IPTC, GPS or a
 * location goes; other text stays. Of the other ancillary chunks only those
 * in PNG_KEPT stay. Critical chunks stay. Bytes after IEND go.
 *
 * WebP (RIFF): EXIF is cleaned as above, and goes whole (its VP8X flag cleared)
 * when it cannot be parsed; "XMP " goes and its VP8X flag is cleared, and so
 * does every chunk but the picture's own (WEBP_KEPT); the RIFF size is
 * rewritten. Bytes after the RIFF go.
 *
 * A chunk or segment whose length does not fit cannot be delimited, and
 * neither can anything after it: it goes with the rest, but for image data cut
 * short (a photo whose end is missing still shows what it has).
 *
 * Anything else (GIF, HEIC, AVIF, TIFF, a video, a document) is returned as
 * given, whatever it carries: HEIC and AVIF can hold the same EXIF GPS and XMP,
 * and that is the sender's to know (James, 2026-10-05: "People have to know
 * what is contained in the files that they're sending").
 *
 * A file that needs no change comes back as the very array given. Nothing here
 * throws: a failure inside returns null, and the caller must not send the file
 * then.
 *
 * Where Kotlin differs from the JS, and why: the web's `subarray` views are
 * copies here (a [ByteArray] has no views), and a result is still its input by
 * identity, or not, as before; a read past the end gives 0 and a write past it
 * is ignored, as a typed array's do; u32 values are Long; a `tiffPass` reader
 * the web makes and never uses is not made; which entries of an IFD are
 * dropped is looked up in step with their order rather than searched; and there
 * is no `instanceof Uint8Array` check, the type says it.
 */
object PhotoMetadata {
    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(s: String) = ByteArray(s.length) { s[it].code.toByte() }

    private val JPEG_START = bytesOf(0xFF, 0xD8, 0xFF)
    private val PNG_SIGNATURE = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_IEND = bytesOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82)
    private val RIFF = ascii("RIFF")
    private val WEBP = ascii("WEBP")

    private val JFIF = ascii("JFIF\u0000")
    private val EXIF = ascii("Exif\u0000")                       // and one more byte, 0 by the standard
    private val ICC_PROFILE = ascii("ICC_PROFILE\u0000")
    private val ISO_GAIN_MAP = ascii("urn:iso:std:iso:ts:21496:-1\u0000")
    private val MPF = ascii("MPF\u0000")
    private val ADOBE = ascii("Adobe")

    /**
     * How deep images inside images are cleaned (the photo is 0, its EXIF
     * thumbnail or an MPF image 1, a thumbnail's thumbnail 2); an EXIF block
     * deeper than this goes whole.
     */
    private const val MAX_DEPTH = 2

    private const val GPS_INFO = 0x8825
    private const val EXIF_IFD = 0x8769
    private const val INTEROP_IFD = 0xA005
    private const val THUMBNAIL_OFFSET = 0x0201
    private const val THUMBNAIL_LENGTH = 0x0202
    private const val STRIP_OFFSETS = 0x0111
    private const val STRIP_BYTE_COUNTS = 0x0117

    /**
     * The EXIF entries that leave, with what they point to: GPSInfo (the GPS
     * IFD), and XMLPacket (XMP), IPTC-NAA and Photoshop's ImageResources
     * (IPTC), which some writers put in the TIFF block instead of APP1/APP13.
     */
    private val DROPPED_TAGS = setOf(GPS_INFO, 0x02BC, 0x83BB, 0x8649)

    /** Bytes per value of TIFF types 1-13 (BYTE, ASCII, SHORT, LONG, RATIONAL, SBYTE, UNDEFINED, SSHORT, SLONG, SRATIONAL, FLOAT, DOUBLE, IFD). */
    private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)

    /** WebP VP8X flags (libwebp WebPFeatureFlags). */
    private const val WEBP_EXIF_FLAG = 0x08
    private const val WEBP_XMP_FLAG = 0x04

    /**
     * The WebP chunks kept as they are, besides the first VP8X (EXIF is kept
     * cleaned): the picture, its alpha, animation and colour profile.
     */
    private val WEBP_KEPT = setOf("VP8 ", "VP8L", "ALPH", "ANIM", "ANMF", "ICCP")

    /** Of those, the image data: kept even when cut short. */
    private val WEBP_IMAGE_DATA = setOf("VP8 ", "VP8L", "ALPH", "ANMF")

    /**
     * The PNG ancillary chunks kept as they are (eXIf is kept cleaned, a text
     * chunk unless its keyword names location metadata): what decoders use to
     * show the picture (colour, transparency, density, animation, Apple's iDOT)
     * and the registered ones that cannot say where. Critical chunks are all
     * kept.
     */
    private val PNG_KEPT = setOf(
        "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP", "mDCV", "mDCv", "cLLI", "cLLi", "bKGD", "hIST", "tRNS",
        "pHYs", "sPLT", "tIME", "acTL", "fcTL", "fdAT", "oFFs", "pCAL", "sCAL", "sTER", "gIFg", "iDOT",
    )

    /** PNG image data: kept even when cut short. */
    private val PNG_IMAGE_DATA = setOf("IDAT", "fdAT")

    /** A PNG chunk type: four ASCII letters. */
    private val PNG_CHUNK_TYPE = Regex("[A-Za-z]{4}")

    /**
     * A PNG text chunk keyword that names metadata which can carry location.
     * The keyword is read as Latin-1, so every character is U+0000-U+00FF, and
     * there IGNORE_CASE is the web's /i (ASCII letters in both cases, nothing
     * else folds to one). `\z` is the web's `$`: Java's also matches before a
     * final line break.
     */
    private val PNG_LOCATION_KEYWORD = Regex(
        "^xml:com\\.adobe\\.xmp\\z|^raw profile type |exif|xmp|iptc|gps|geo|locat|latitude|longitude",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [bytes] as they may leave: a JPEG, PNG or WebP without the metadata that
     * can carry where it was taken, anything else as given. The same array when
     * nothing changes, a new array when something does, and null when it is one
     * of those formats and something failed inside (do not send it then).
     */
    fun withoutLocation(bytes: ByteArray): ByteArray? {
        try {
            val edits = when {
                startsWith(bytes, JPEG_START) -> jpegEdits(bytes, 0)
                startsWith(bytes, PNG_SIGNATURE) -> pngEdits(bytes)
                startsWith(bytes, RIFF) && startsWith(bytes, WEBP, 8) -> webpEdits(bytes)
                else -> return bytes
            }
            return applyEdits(bytes, edits)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    // ── bytes ───────────────────────────────────────────────────────────────

    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

    /** The byte at [i], or -1 past either end (what JS's `undefined` is to a comparison). */
    private fun byteAt(bytes: ByteArray, i: Long): Int = if (i >= 0 && i < bytes.size) bytes.u8(i.toInt()) else -1

    private fun startsWith(bytes: ByteArray, prefix: ByteArray, at: Int = 0, end: Int = bytes.size): Boolean {
        if (at < 0 || at + prefix.size > end) return false
        for (i in prefix.indices) if (bytes.getOrNull(at + i) != prefix[i]) return false
        return true
    }

    /** u16 and u32 reads in one byte order; a byte past either end reads as 0, as `undefined | 0` does in JS. */
    private class Reader(private val bytes: ByteArray, private val littleEndian: Boolean) {
        private fun at(o: Int): Long = if (o >= 0 && o < bytes.size) bytes.u8(o).toLong() else 0L

        fun u16(o: Int): Int =
            (if (littleEndian) at(o) or (at(o + 1) shl 8) else (at(o) shl 8) or at(o + 1)).toInt()

        fun u32(o: Int): Long =
            if (littleEndian) at(o) or (at(o + 1) shl 8) or (at(o + 2) shl 16) or (at(o + 3) shl 24)
            else (at(o) shl 24) or (at(o + 1) shl 16) or (at(o + 2) shl 8) or at(o + 3)
    }

    /** u16 and u32 writes in one byte order; a byte past either end is not written, as in a typed array. */
    private class Writer(private val bytes: ByteArray, private val littleEndian: Boolean) {
        private fun put(o: Int, v: Long, n: Int) {
            for (i in 0 until n) {
                val at = o + (if (littleEndian) i else n - 1 - i)
                if (at >= 0 && at < bytes.size) bytes[at] = ((v ushr (8 * i)) and 0xFF).toByte()
            }
        }

        fun u16(o: Int, v: Int) = put(o, v.toLong(), 2)
        fun u32(o: Int, v: Long) = put(o, v, 4)
    }

    /** A plain copy of [start, end). */
    private fun copyOf(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): ByteArray = bytes.copyOfRange(start, end)

    /** What the web holds as `bytes.subarray(start, end)`, as a copy: empty when [start] is not before [end], cut at the end of the array. */
    private fun subarray(bytes: ByteArray, start: Int, end: Int = bytes.size): ByteArray {
        val to = minOf(end, bytes.size)
        return if (start >= to) ByteArray(0) else bytes.copyOfRange(start, to)
    }

    private fun indexOf(bytes: ByteArray, value: Int, from: Int): Int {
        for (i in maxOf(from, 0) until bytes.size) if (bytes.u8(i) == value) return i
        return -1
    }

    private fun latin1(bytes: ByteArray, start: Int, end: Int): String = String(bytes, start, end - start, Charsets.ISO_8859_1)

    /**
     * One change: [start, end) dropped, or, with [replace], overwritten by
     * [replace], which is exactly as long.
     */
    internal class Edit(val start: Int, val end: Int, val replace: ByteArray? = null)

    /**
     * [bytes] with [edits] made. Returns [bytes] itself when there is nothing
     * to do.
     */
    internal fun applyEdits(bytes: ByteArray, edits: MutableList<Edit>): ByteArray {
        if (edits.isEmpty()) return bytes
        edits.sortBy { it.start }
        var length = bytes.size
        var from = 0
        for (e in edits) {
            if (e.start < from || e.end < e.start || e.end > bytes.size) throw IllegalStateException("edits overlap or overrun")
            val replace = e.replace
            if (replace != null) {
                if (replace.size != e.end - e.start) throw IllegalStateException("a replacement changes the length")
            } else {
                length -= e.end - e.start
            }
            from = e.end
        }
        val out = ByteArray(length)
        var src = 0
        var dst = 0
        for (e in edits) {
            System.arraycopy(bytes, src, out, dst, e.start - src)
            dst += e.start - src
            val replace = e.replace
            if (replace != null) {
                System.arraycopy(replace, 0, out, dst, replace.size)
                dst += replace.size
            }
            src = e.end
        }
        System.arraycopy(bytes, src, out, dst, bytes.size - src)
        return out
    }

    /** Where position [p] of the input lands once [edits] are made. */
    private fun newPosition(edits: List<Edit>, p: Int): Int {
        var dropped = 0
        for (e in edits) if (e.replace == null && e.end <= p) dropped += e.end - e.start
        return p - dropped
    }

    /** JS's `fill(value, start, end)`: the range is cut to the array, and nothing is filled when it is empty. */
    private fun fill(a: ByteArray, value: Byte, start: Int, end: Int) {
        val from = start.coerceIn(0, a.size)
        val to = end.coerceIn(0, a.size)
        if (from < to) a.fill(value, from, to)
    }

    private fun fill(a: BooleanArray, value: Boolean, start: Int, end: Int) {
        val from = start.coerceIn(0, a.size)
        val to = end.coerceIn(0, a.size)
        if (from < to) a.fill(value, from, to)
    }

    // ── EXIF (a TIFF block) ─────────────────────────────────────────────────

    /**
     * An EXIF TIFF block without GPS, as a copy of the same length (so every
     * offset in it still holds): every entry of DROPPED_TAGS leaves its IFD, the
     * other entries moving up; then every byte that no IFD left in it references
     * becomes zero, and so does everything a dropped entry pointed to. That takes
     * the GPS IFD however it lies, GPS a naive stripper left behind without its
     * pointer, and a segment a wrong length swallowed: the block keeps what its
     * structure says is in it, and nothing else. The thumbnail is cleaned in its
     * place. [tiff] itself when there is nothing to do; null when the block
     * cannot be parsed (the caller then drops it whole).
     *
     * A pass is made again on what it gives until nothing changes: in a broken
     * block, what a dropped entry pointed to can overlap an IFD that is kept,
     * and the values that IFD pointed to are then referenced by nothing. A block
     * that has not settled after four passes goes whole.
     */
    private fun tiffWithoutGps(tiff: ByteArray, depth: Int): ByteArray? {
        var current = tiff
        for (pass in 0 until 4) {
            val next = tiffPass(current, depth)
            if (next == null || next === current) return next
            current = next
        }
        return null
    }

    /** One pass of [tiffWithoutGps]. */
    private fun tiffPass(tiff: ByteArray, depth: Int): ByteArray? {
        val layout = tiffLayout(tiff) ?: return null
        var thumbnail: ByteArray? = null
        val inside = layout.thumbnail
        if (inside != null) {
            val original = copyOf(tiff, inside.start, inside.end)
            val clean = applyEdits(original, jpegEdits(original, depth + 1))
            if (clean !== original) thumbnail = clean
        }
        val referenced = BooleanArray(tiff.size)
        for (span in layout.keep) fill(referenced, true, span.start, span.end)
        val dropping = layout.ifds.any { it.drop.isNotEmpty() }
        val stray = tiff.indices.any { tiff[it] != 0.toByte() && !referenced[it] }
        if (!dropping && !stray && thumbnail == null) return tiff

        val out = copyOf(tiff)
        val w = Writer(out, layout.le)
        // The dropped entries leave their IFDs: the others move up, the next-IFD
        // offset follows them.
        for (ifd in layout.ifds) {
            if (ifd.drop.isEmpty()) continue
            var p = ifd.at + 2
            var d = 0                                            // ifd.drop is in ascending order
            for (k in 0 until ifd.count) {
                if (d < ifd.drop.size && ifd.drop[d] == k) {
                    d++
                    continue
                }
                val e = ifd.at + 2 + 12 * k
                System.arraycopy(tiff, e, out, p, 12)
                p += 12
            }
            val next = ifd.at + 2 + 12 * ifd.count
            System.arraycopy(tiff, next, out, p, 4)
            w.u16(ifd.at, ifd.count - ifd.drop.size)
        }
        // What nothing references becomes zeros (the freed entries among it), and
        // so does what the dropped entries pointed to, wherever it lies.
        for (i in out.indices) if (!referenced[i]) out[i] = 0
        for (span in layout.wipe) fill(out, 0, span.start, span.end)
        // The thumbnail, cleaned, where it was; its length entry says how long.
        if (thumbnail != null && inside != null) {
            System.arraycopy(thumbnail, 0, out, inside.start, thumbnail.size)
            fill(out, 0, inside.start + thumbnail.size, inside.end)
            val o = Reader(out, layout.le)
            var k = 0
            while (k < o.u16(inside.ifd)) {
                val e = inside.ifd + 2 + 12 * k
                if (o.u16(e) == THUMBNAIL_LENGTH) {
                    if (o.u16(e + 2) == 3) w.u16(e + 8, thumbnail.size) else w.u32(e + 8, thumbnail.size.toLong())
                }
                k++
            }
        }
        // What leaves must parse, with nothing of DROPPED_TAGS left in it.
        val check = tiffLayout(out)
        if (check == null || check.ifds.any { it.drop.isNotEmpty() }) return null
        return out
    }

    /** A range [start, end) of a block. */
    private class Span(val start: Int, val end: Int)

    /** An IFD at [at] with [count] entries, the positions (k) of those that are dropped, in order. */
    private class Ifd(val at: Int, val count: Int) {
        val drop = ArrayList<Int>()
    }

    /** The IFD1 thumbnail inside the block: [start, end) and the IFD that says so. */
    private class Thumbnail(val start: Int, val end: Int, val ifd: Int)

    private class Layout(val le: Boolean, val ifds: List<Ifd>, val keep: List<Span>, val wipe: List<Span>, val thumbnail: Thumbnail?)

    private enum class Kind { IFD0, IFD1, EXIF, INTEROP, CHAIN }

    private class Queued(val at: Long, val kind: Kind)

    /**
     * A TIFF block's structure, as far as EXIF has one: the IFDs reachable from
     * IFD0 (its chain, the Exif and Interoperability IFDs), the entries of
     * DROPPED_TAGS in each, the ranges the block keeps once those are gone (the
     * header, each IFD, each other entry's value, the IFD1 thumbnail or strips),
     * the ranges what the dropped entries point to occupy (`wipe`: the GPS IFD
     * and its values, XMP, IPTC), and the IFD1 thumbnail when it is a JPEG
     * inside the block. null when it cannot be parsed: a bad header, an IFD0
     * outside the block, an IFD the block's end cuts, or an Exif or
     * Interoperability pointer that is not one. An IFD that lies wholly outside
     * the block is not in it, and is passed over; so is a value.
     */
    private fun tiffLayout(t: ByteArray): Layout? {
        val len = t.size
        if (len < 8) return null
        val le = when {
            t[0] == 0x49.toByte() && t[1] == 0x49.toByte() -> true
            t[0] == 0x4D.toByte() && t[1] == 0x4D.toByte() -> false
            else -> return null
        }
        val r = Reader(t, le)
        if (r.u16(2) != 42) return null

        fun pointer(e: Int): Long? {
            val type = r.u16(e + 2)
            if (r.u32(e + 4) != 1L) return null
            if (type == 3) return r.u16(e + 8).toLong()
            if (type == 4 || type == 13) return r.u32(e + 8)
            return null
        }

        /** Where an entry's value lies outside the entry, or null. */
        fun valueRange(e: Int): Span? {
            val type = r.u16(e + 2)
            if (!(type in 1..13)) return null
            val size = TYPE_SIZE[type].toLong() * r.u32(e + 4)
            if (size <= 4) return null
            val at = r.u32(e + 8)
            return if (at >= 8 && at + size <= len) Span(at.toInt(), (at + size).toInt()) else null
        }

        /** A SHORT or LONG entry's values. */
        fun numbers(e: Int): List<Long> {
            val type = r.u16(e + 2)
            val count = r.u32(e + 4)
            val size = if (type == 3) 2 else if (type == 4) 4 else 0
            if (size == 0 || count > len) return emptyList()
            val at = if (count * size <= 4) (e + 8).toLong() else r.u32(e + 8)
            if (at + count * size > len) return emptyList()
            return List(count.toInt()) { i -> if (size == 2) r.u16((at + 2 * i).toInt()).toLong() else r.u32((at + 4 * i).toInt()) }
        }

        val ifds = ArrayList<Ifd>()
        val keep = arrayListOf(Span(0, 8))
        val wipe = ArrayList<Span>()
        var thumbnail: Thumbnail? = null
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Queued>()
        queue.add(Queued(r.u32(4), Kind.IFD0))
        while (queue.isNotEmpty()) {
            val queued = queue.removeFirst()
            val at = queued.at
            val kind = queued.kind
            if (!seen.add(at)) continue
            if (at < 8 || at + 2 > len) {
                if (kind == Kind.IFD0) return null
                continue
            }
            val a = at.toInt()
            val count = r.u16(a)
            val next = a + 2 + 12 * count
            if (next + 4 > len) return null
            val ifd = Ifd(a, count)
            var thumbOffset: Long? = null
            var thumbLength: Long? = null
            var strips: List<Long>? = null
            var stripLengths: List<Long>? = null
            for (k in 0 until count) {
                val e = a + 2 + 12 * k
                val tag = r.u16(e)
                if (tag in DROPPED_TAGS) {
                    ifd.drop.add(k)
                    if (tag == GPS_INFO) {
                        val g = pointer(e)
                        if (g != null) wipe.addAll(gpsRanges(t, le, g))
                    } else {
                        val range = valueRange(e)
                        if (range != null) wipe.add(range)
                    }
                    continue
                }
                val range = valueRange(e)
                if (range != null) keep.add(range)
                if (tag == EXIF_IFD && kind != Kind.EXIF && kind != Kind.INTEROP) {
                    val p = pointer(e) ?: return null
                    queue.add(Queued(p, Kind.EXIF))
                } else if (tag == INTEROP_IFD && kind == Kind.EXIF) {
                    val p = pointer(e) ?: return null
                    queue.add(Queued(p, Kind.INTEROP))
                } else if (kind == Kind.IFD1 && tag == THUMBNAIL_OFFSET) {
                    thumbOffset = pointer(e)
                } else if (kind == Kind.IFD1 && tag == THUMBNAIL_LENGTH) {
                    thumbLength = pointer(e)
                } else if (tag == STRIP_OFFSETS) {
                    strips = numbers(e)
                } else if (tag == STRIP_BYTE_COUNTS) {
                    stripLengths = numbers(e)
                }
            }
            keep.add(Span(a, a + 2 + 12 * (count - ifd.drop.size) + 4))
            ifds.add(ifd)
            if (kind == Kind.IFD0 || kind == Kind.IFD1 || kind == Kind.CHAIN) {
                val following = r.u32(next)
                if (following != 0L) queue.add(Queued(following, if (kind == Kind.IFD0) Kind.IFD1 else Kind.CHAIN))
            }
            val offset = thumbOffset
            val length = thumbLength
            if (offset != null && length != null && length != 0L && offset >= 8 && offset + length <= len) {
                keep.add(Span(offset.toInt(), (offset + length).toInt()))
                if (byteAt(t, offset) == 0xFF && byteAt(t, offset + 1) == 0xD8) {
                    thumbnail = Thumbnail(offset.toInt(), (offset + length).toInt(), a)
                }
            }
            val starts = strips
            val lengths = stripLengths
            if (starts != null && lengths != null && starts.size == lengths.size) {
                for (i in starts.indices) {
                    val s = starts[i]
                    if (s >= 8 && s + lengths[i] <= len) keep.add(Span(s.toInt(), (s + lengths[i]).toInt()))
                }
            }
        }
        return Layout(le, ifds, keep, wipe, thumbnail)
    }

    /**
     * The ranges a GPS IFD at [at] and its values occupy in [t], as far as
     * they are in it and can be read.
     */
    private fun gpsRanges(t: ByteArray, le: Boolean, at: Long): List<Span> {
        val len = t.size
        if (at < 8 || at + 2 > len) return emptyList()
        val a = at.toInt()
        val r = Reader(t, le)
        val count = r.u16(a)
        val ranges = arrayListOf(Span(a, minOf(len, a + 2 + 12 * count + 4)))
        for (k in 0 until count) {
            if (a + 2 + 12 * (k + 1) > len) break
            val e = a + 2 + 12 * k
            val type = r.u16(e + 2)
            if (!(type in 1..13)) continue
            val size = TYPE_SIZE[type].toLong() * r.u32(e + 4)
            val value = r.u32(e + 8)
            if (size > 4 && value >= 8 && value + size <= len) ranges.add(Span(value.toInt(), (value + size).toInt()))
        }
        return ranges
    }

    // ── JPEG ────────────────────────────────────────────────────────────────

    /** What becomes of a segment or chunk, when it is not kept (null). */
    private sealed class Verdict {
        object Drop : Verdict()

        /** An APP1 EXIF segment's TIFF block, cleaned. */
        class Edited(val edit: Edit) : Verdict()

        /** An APP2 MPF segment that can be read: kept, and the images it lists after EOI with it. */
        class Indexed(val mpf: Mpf) : Verdict()

        /** A PNG chunk's data and CRC anew. */
        class Anew(val chunk: ByteArray) : Verdict()
    }

    /**
     * The edits that clean the JPEG in [bytes] (SOI at 0), at [depth] (0 the
     * photo, deeper an image inside it, which keeps no MPF and nothing after its
     * EOI).
     */
    private fun jpegEdits(bytes: ByteArray, depth: Int): MutableList<Edit> {
        val len = bytes.size
        val edits = ArrayList<Edit>()
        var mpf: Mpf? = null
        var imageEnd = len
        var pos = 2
        while (pos < len) {
            if (bytes.u8(pos) != 0xFF) {
                // Not a marker: bytes a decoder skips to the next one.
                val next = nextMarker(bytes, pos)
                edits.add(Edit(pos, next))
                pos = next
                continue
            }
            var m = pos
            while (m + 1 < len && bytes.u8(m + 1) == 0xFF) m++         // fill bytes
            if (m + 1 >= len) {
                edits.add(Edit(pos, len))
                break
            }
            val code = bytes.u8(m + 1)
            val body = m + 2
            if (code == 0x00) {
                val next = nextMarker(bytes, body)
                edits.add(Edit(pos, next))
                pos = next
                continue
            }
            if (code == 0xD9) {                                         // EOI
                imageEnd = body
                break
            }
            if (code == 0xD8) {                                         // a second SOI: nothing after it is this image
                edits.add(Edit(pos, len))
                break
            }
            if (code == 0x01 || (code in 0xD0..0xD7)) {                 // TEM, RSTn: no length
                pos = body
                continue
            }
            val end = if (body + 2 <= len) body + ((bytes.u8(body) shl 8) or bytes.u8(body + 1)) else -1
            if (end < body + 2 || end > len) {
                // A length that does not fit: nothing after it can be delimited.
                edits.add(Edit(pos, len))
                break
            }
            if (code == 0xDA) {                                         // SOS: the entropy-coded data follows
                pos = scanEnd(bytes, end)
                continue
            }
            if (code < 0xC0 || code == 0xC8) {                          // reserved: no decoder reads it
                edits.add(Edit(pos, end))
                pos = end
                continue
            }
            if (code in 0xE0..0xEF) {
                when (val verdict = appSegment(code, bytes, body + 2, end, depth, mpf == null)) {
                    Verdict.Drop -> edits.add(Edit(pos, end))
                    is Verdict.Edited -> edits.add(verdict.edit)
                    is Verdict.Indexed -> mpf = verdict.mpf.located(pos, end)
                    else -> {}
                }
            }
            pos = end
        }

        val index = mpf
        if (index != null) {
            val listed = mpfImages(bytes, index, imageEnd)
            if (listed != null) {
                var cursor = imageEnd
                for (image in listed.images) {
                    if (image.start > cursor) edits.add(Edit(cursor, image.start))
                    for (e in jpegEdits(subarray(bytes, image.start, image.end), depth + 1)) {
                        edits.add(Edit(e.start + image.start, e.end + image.start, e.replace))
                    }
                    cursor = image.end
                }
                if (cursor < len) edits.add(Edit(cursor, len))
                val patch = mpfPatch(bytes, index, listed, imageEnd, edits)
                if (patch != null) edits.add(patch)
                return edits
            }
            // An index that does not describe this file goes, with what follows.
            edits.add(Edit(index.start, index.end))
        }
        if (imageEnd < len) edits.add(Edit(imageEnd, len))
        return edits
    }

    /**
     * The first marker at or after [from] outside entropy-coded data (an FF not
     * followed by 00), or the end.
     */
    private fun nextMarker(bytes: ByteArray, from: Int): Int {
        val len = bytes.size
        var i = from
        while (true) {
            i = indexOf(bytes, 0xFF, i)
            if (i < 0) return len
            var j = i + 1
            while (j < len && bytes.u8(j) == 0xFF) j++
            if (j >= len) return len
            if (bytes.u8(j) != 0x00) return i
            i = j + 1
        }
    }

    /**
     * Where the entropy-coded data starting at [from] ends: the first marker that
     * is not a stuffed FF 00 or a restart marker, or the end.
     */
    private fun scanEnd(bytes: ByteArray, from: Int): Int {
        val len = bytes.size
        var i = from
        while (true) {
            i = indexOf(bytes, 0xFF, i)
            if (i < 0) return len
            var j = i + 1
            while (j < len && bytes.u8(j) == 0xFF) j++
            if (j >= len) return len
            val b = bytes.u8(j)
            if (b != 0x00 && !(b in 0xD0..0xD7)) return i
            i = j + 1
        }
    }

    /** What becomes of an APPn segment whose payload is [payload, end): null to keep it. */
    private fun appSegment(code: Int, bytes: ByteArray, payload: Int, end: Int, depth: Int, mpfFree: Boolean): Verdict? {
        if (code == 0xE0) return if (startsWith(bytes, JFIF, payload, end)) null else Verdict.Drop
        if (code == 0xE1) {
            if (depth > MAX_DEPTH || !startsWith(bytes, EXIF, payload, end) || payload + 6 > end) return Verdict.Drop
            val tiff = subarray(bytes, payload + 6, end)
            val clean = tiffWithoutGps(tiff, depth) ?: return Verdict.Drop
            return if (clean === tiff) null else Verdict.Edited(Edit(payload + 6, end, clean))
        }
        if (code == 0xE2) {
            if (startsWith(bytes, ICC_PROFILE, payload, end) || startsWith(bytes, ISO_GAIN_MAP, payload, end)) return null
            if (depth == 0 && mpfFree && startsWith(bytes, MPF, payload, end)) {
                val mpf = parseMpf(bytes, payload, end)
                return if (mpf != null) Verdict.Indexed(mpf) else Verdict.Drop
            }
            return Verdict.Drop
        }
        if (code == 0xEE) return if (startsWith(bytes, ADOBE, payload, end)) null else Verdict.Drop
        return Verdict.Drop
    }

    /**
     * An MPF index (CIPA DC-007): its byte order, the MP header it counts offsets
     * from, and where its MP entries are ([start], [end]: the segment, once it is
     * known).
     */
    private class Mpf(val base: Int, val le: Boolean, val entries: Int, val count: Int, val start: Int = 0, val end: Int = 0) {
        fun located(start: Int, end: Int) = Mpf(base, le, entries, count, start, end)
    }

    /** An MPF index's own image or another's entry: where its MP entry is, and the size it says. */
    private class MpEntry(val at: Int, val size: Long)

    /** An image the MPF index lists after the photo: its MP entry and where it lies. */
    private class MpImage(val at: Int, val start: Int, val end: Int)

    private class Listed(val first: MpEntry, val images: List<MpImage>)

    /** An MPF index: null when it cannot be read. */
    private fun parseMpf(bytes: ByteArray, payload: Int, end: Int): Mpf? {
        val base = payload + 4
        if (base + 8 > end) return null
        val le = when {
            bytes.u8(base) == 0x49 && bytes.u8(base + 1) == 0x49 -> true
            bytes.u8(base) == 0x4D && bytes.u8(base + 1) == 0x4D -> false
            else -> return null
        }
        val r = Reader(bytes, le)
        if (r.u16(base + 2) != 42) return null
        val ifdAt = base + r.u32(base + 4)
        if (ifdAt + 2 > end) return null
        val ifd = ifdAt.toInt()
        val n = r.u16(ifd)
        if (ifd + 2 + 12 * n > end) return null
        var count: Long? = null
        var entries: Long? = null
        var length: Long? = null
        for (k in 0 until n) {
            val e = ifd + 2 + 12 * k
            val tag = r.u16(e)
            if (tag == 0xB001 && r.u16(e + 2) == 4 && r.u32(e + 4) == 1L) {
                count = r.u32(e + 8)
            } else if (tag == 0xB002 && r.u16(e + 2) == 7) {
                length = r.u32(e + 4)
                entries = base + r.u32(e + 8)
            }
        }
        if (count == null || count == 0L || entries == null || length == null || length != 16 * count || entries + length > end) return null
        return Mpf(base, le, entries.toInt(), count.toInt())
    }

    /**
     * The images an MPF index lists after the photo's EOI, in file order, or
     * null when the index does not describe this file.
     */
    private fun mpfImages(bytes: ByteArray, mpf: Mpf, imageEnd: Int): Listed? {
        val r = Reader(bytes, mpf.le)
        var first: MpEntry? = null
        val images = ArrayList<MpImage>()
        for (i in 0 until mpf.count) {
            val at = mpf.entries + 16 * i
            val size = r.u32(at + 4)
            val offset = r.u32(at + 8)
            if (i == 0) {
                if (offset != 0L) return null
                first = MpEntry(at, size)
                continue
            }
            if (size == 0L) continue
            val start = mpf.base + offset
            val end = start + size
            if (offset == 0L || start < imageEnd || end > bytes.size || byteAt(bytes, start) != 0xFF || byteAt(bytes, start + 1) != 0xD8) return null
            images.add(MpImage(at, start.toInt(), end.toInt()))
        }
        images.sortBy { it.start }
        for (i in 1 until images.size) if (images[i].start < images[i - 1].end) return null
        return Listed(first!!, images)
    }

    /**
     * The MP entries rewritten for where the photo ends and its images are once
     * [edits] are made, or null when they do not change.
     */
    private fun mpfPatch(bytes: ByteArray, mpf: Mpf, listed: Listed, imageEnd: Int, edits: List<Edit>): Edit? {
        fun at(p: Int) = newPosition(edits, p)
        val start = mpf.entries
        val end = start + 16 * mpf.count
        val block = copyOf(bytes, start, end)
        val w = Writer(block, mpf.le)
        if (listed.first.size == imageEnd.toLong()) w.u32(listed.first.at - start + 4, at(imageEnd).toLong())
        for (image in listed.images) {
            w.u32(image.at - start + 4, (at(image.end) - at(image.start)).toLong())
            w.u32(image.at - start + 8, (at(image.start) - at(mpf.base)).toLong())
        }
        for (i in block.indices) if (block[i] != bytes[start + i]) return Edit(start, end, block)
        return null
    }

    // ── PNG ─────────────────────────────────────────────────────────────────

    private fun pngEdits(bytes: ByteArray): MutableList<Edit> {
        val len = bytes.size
        val r = Reader(bytes, false)
        val edits = ArrayList<Edit>()
        var pos = PNG_SIGNATURE.size
        while (pos < len) {
            val size = if (pos + 12 <= len) r.u32(pos) else -1L
            val type = if (size >= 0) latin1(bytes, pos + 4, pos + 8) else ""
            val data = pos + 8
            val next = data + size + 4
            // Not a chunk (or one cut short) cannot be delimited, and nothing after
            // it can: it goes with the rest, but for image data cut short, which
            // still shows what it has. A file that ends in IEND was not cut: its
            // image data's length is wrong, and it would take what follows.
            if (!PNG_CHUNK_TYPE.matches(type) || next > len || (type == "IHDR" && size != 13L)) {
                val cut = next > len && type in PNG_IMAGE_DATA && !startsWith(bytes, PNG_IEND, len - PNG_IEND.size)
                if (!cut) edits.add(Edit(pos, len))
                break
            }
            val chunkEnd = next.toInt()
            val verdict = pngChunk(type, bytes, data, data + size.toInt())
            if (verdict === Verdict.Drop) edits.add(Edit(pos, chunkEnd))
            else if (verdict is Verdict.Anew) edits.add(Edit(data, chunkEnd, verdict.chunk))
            pos = chunkEnd
            if (type == "IEND") {
                if (pos < len) edits.add(Edit(pos, len))
                break
            }
        }
        return edits
    }

    /** What becomes of a PNG chunk: null to keep it, a drop, or its data and CRC anew. */
    private fun pngChunk(type: String, bytes: ByteArray, data: Int, end: Int): Verdict? {
        if ((type[0].code and 0x20) == 0) return null                   // critical: the picture itself
        if (type == "eXIf") {
            // A TIFF block, behind EXIF's JPEG header when a writer put one there.
            val tiffAt = if (startsWith(bytes, EXIF, data, end)) data + 6 else data
            val tiff = subarray(bytes, tiffAt, end)
            val clean = tiffWithoutGps(tiff, 0) ?: return Verdict.Drop
            if (clean === tiff) return null
            val chunk = ByteArray(end - data + 4)
            System.arraycopy(bytes, data, chunk, 0, tiffAt - data)
            System.arraycopy(clean, 0, chunk, tiffAt - data, clean.size)
            val crc = crc32(chunk, 0, end - data, crc32(bytes, data - 4, data))
            Writer(chunk, false).u32(end - data, crc)
            return Verdict.Anew(chunk)
        }
        if (type == "tEXt" || type == "zTXt" || type == "iTXt") {
            var nul = -1
            for (i in data until minOf(end, data + 80)) {
                if (bytes[i] == 0.toByte()) {
                    nul = i - data
                    break
                }
            }
            if (nul < 1) return Verdict.Drop                            // no keyword to go by
            return if (PNG_LOCATION_KEYWORD.containsMatchIn(latin1(bytes, data, data + nul))) Verdict.Drop else null
        }
        return if (type in PNG_KEPT) null else Verdict.Drop
    }

    private val CRC_TABLE: IntArray by lazy {
        IntArray(256) { n ->
            var c = n
            for (k in 0 until 8) c = if ((c and 1) != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            c
        }
    }

    /**
     * CRC-32 (ISO 3309, as PNG uses it) of [start, end), continuing [previous]
     * (a finished CRC) when given.
     */
    private fun crc32(bytes: ByteArray, start: Int, end: Int, previous: Long = 0): Long {
        var c = previous.toInt() xor -1
        for (i in start until end) c = CRC_TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        return (c xor -1).toLong() and 0xFFFFFFFFL
    }

    // ── WebP ────────────────────────────────────────────────────────────────

    private fun webpEdits(bytes: ByteArray): MutableList<Edit> {
        val len = bytes.size
        val r = Reader(bytes, true)
        val edits = ArrayList<Edit>()
        val riffSize = r.u32(4)
        val riffEnd = 8 + riffSize
        val end = minOf(len.toLong(), riffEnd).toInt()
        var pos = 12
        var vp8x = -1
        var exifKept = 0
        var exifDropped = 0
        var xmpDropped = 0
        while (pos < end) {
            val fourcc = if (pos + 8 <= end) latin1(bytes, pos, pos + 4) else ""
            val size = if (fourcc.isNotEmpty()) r.u32(pos + 4) else 0L
            val data = pos + 8
            val dataEnd = data + size
            // A chunk cut short, or a VP8X that is not the 10 bytes it always
            // is, cannot be delimited, and nothing after it can: it goes with
            // the rest, but for image data cut short, which still shows what it
            // has. Image data that runs past the RIFF itself was not cut: its
            // size is wrong, and it would take what follows.
            if (fourcc.isEmpty() || dataEnd > end || (fourcc == "VP8X" && size != 10L)) {
                if (!(dataEnd > end && dataEnd <= riffEnd && fourcc in WEBP_IMAGE_DATA)) {
                    edits.add(Edit(pos, end))
                    if (fourcc == "EXIF") exifDropped++
                    if (fourcc == "XMP ") xmpDropped++
                }
                break
            }
            val chunkEnd = dataEnd.toInt()
            val next = minOf(dataEnd + (size and 1L), end.toLong()).toInt()
            if (fourcc == "VP8X" && vp8x < 0) {
                vp8x = data
            } else if (fourcc == "EXIF") {
                // A TIFF block, behind EXIF's JPEG header when a writer put one there.
                val tiffAt = if (startsWith(bytes, EXIF, data, chunkEnd)) data + 6 else data
                val tiff = subarray(bytes, tiffAt, chunkEnd)
                val clean = tiffWithoutGps(tiff, 0)
                if (clean == null) {
                    edits.add(Edit(pos, next))
                    exifDropped++
                } else {
                    exifKept++
                    if (clean !== tiff) edits.add(Edit(tiffAt, chunkEnd, clean))
                }
            } else if (fourcc !in WEBP_KEPT) {
                // XMP, and every chunk this does not read (a second VP8X among them).
                edits.add(Edit(pos, next))
                if (fourcc == "XMP ") xmpDropped++
            }
            pos = next
        }
        if (vp8x >= 0) {
            var flags = bytes.u8(vp8x)
            if (xmpDropped != 0) flags = flags and WEBP_XMP_FLAG.inv()
            if (exifDropped != 0 && exifKept == 0) flags = flags and WEBP_EXIF_FLAG.inv()
            if (flags != bytes.u8(vp8x)) edits.add(Edit(vp8x, vp8x + 1, byteArrayOf(flags.toByte())))
        }
        var dropped = 0
        for (e in edits) if (e.replace == null) dropped += e.end - e.start
        if (dropped != 0) {
            val size = ByteArray(4)
            Writer(size, true).u32(0, riffSize - dropped)
            edits.add(Edit(4, 8, size))
        }
        if (riffEnd < len) edits.add(Edit(riffEnd.toInt(), len))        // after the RIFF
        return edits
    }
}
