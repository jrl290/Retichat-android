package com.newendian.retichat

import com.newendian.retichat.bridge.MiniJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.Random

/**
 * A photo leaves without where it was taken (James, 2026-10-05): JPEG, PNG and
 * WebP lose their location-bearing metadata with nothing decoded or
 * re-encoded, every other format goes exactly as given.
 *
 * PhotoMetadata is a port of the web's lib/photo_metadata.js, and is held to
 * it by vectors: app/src/test/resources/photo_location_vectors.json, written
 * by scripts/photo_location_vectors.mjs from the web's own function (its tests'
 * photos, an input for every branch of it, cuts and bytes changed, what no
 * photo is). Each is `{name, input, expect}`: "same" when the web hands back
 * the very object it was given, null when it fails (the file must not be
 * sent), else the SHA-256 and length of the bytes it gives. Every one must be
 * what the web says, to the byte.
 */
class PhotoMetadataTest {
    private class Vector(val name: String, val input: ByteArray, val expect: Any?) {
        /** The SHA-256 (hex) and length the web gives, when it gives bytes. */
        val bytes: Pair<String, Long>? get() = (expect as? Map<*, *>)?.let { it["sha256"] as String to it["length"] as Long }
    }

    private val vectors: List<Vector> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("photo_location_vectors.json")
        // A missing file fails the test: a skipped contract check reads as a pass.
        assertNotNull("photo_location_vectors.json is not in the test resources (scripts/photo_location_vectors.mjs writes it)", stream)
        val json = MiniJson.parse(stream!!.use { String(it.readBytes(), Charsets.UTF_8) }) as List<*>
        json.map { entry ->
            val e = entry as Map<*, *>
            Vector(e["name"] as String, Base64.getDecoder().decode(e["input"] as String), e["expect"])
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun isPhotoFormat(b: ByteArray): Boolean {
        fun starts(prefix: List<Int>, at: Int = 0) = b.size >= at + prefix.size && prefix.indices.all { (b[at + it].toInt() and 0xFF) == prefix[it] }
        return starts(listOf(0xFF, 0xD8, 0xFF)) ||
            starts(listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) ||
            (starts("RIFF".map { it.code }) && starts("WEBP".map { it.code }, 8))
    }

    @Test
    fun everyVectorIsWhatTheWebGives() {
        assertTrue("${vectors.size} vectors", vectors.size >= 1000)
        val failures = ArrayList<String>()
        for (v in vectors) {
            val out = PhotoMetadata.withoutLocation(v.input)
            val wrong = when {
                v.expect == "same" -> if (out === v.input) null else "not the same array (${out?.size} bytes)"
                v.expect == null -> if (out == null) null else "not null (${out.size} bytes)"
                out == null -> "null"
                out === v.input -> "the same array"
                else -> {
                    val (hash, length) = v.bytes!!
                    if (out.size.toLong() != length) "${out.size} bytes, the web gives $length"
                    else if (sha256(out) != hash) "$length bytes, but not the web's"
                    else null
                }
            }
            if (wrong != null) failures.add("${v.name}: $wrong")
        }
        assertEquals("${failures.size} of ${vectors.size} vectors differ:\n" + failures.take(20).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun theVectorsCoverWhatTheyShould() {
        val same = vectors.count { it.expect == "same" }
        val cleaned = vectors.count { it.bytes != null }
        assertTrue("$same vectors the web hands back as given", same >= 50)
        assertTrue("$cleaned vectors the web cleans", cleaned >= 800)
        // JPEG, PNG and WebP, each cleaned and each cut short.
        for (kind in listOf("jpeg", "mpf", "progressive", "png", "webp", "thumb")) {
            assertTrue(kind, vectors.any { it.name == "base: $kind" })
            assertTrue("cuts of $kind", vectors.count { it.name.startsWith("cut $kind at ") } >= 20)
            assertTrue("changes of $kind", vectors.count { it.name.startsWith("changed $kind ") } >= 20)
        }
        assertTrue("what no photo is", vectors.count { it.name.startsWith("not an image: ") } >= 20)
    }

    @Test
    fun aPhotoCleanedOnceHasNothingLeftToClean() {
        var cleaned = 0
        for (v in vectors) {
            val out = PhotoMetadata.withoutLocation(v.input) ?: continue
            if (out === v.input) continue
            cleaned++
            assertSame("${v.name}: cleaned again", out, PhotoMetadata.withoutLocation(out))
        }
        assertTrue("$cleaned cleaned", cleaned >= 800)
    }

    @Test
    fun whatIsNoJpegPngOrWebpComesBackAsTheVeryArray() {
        var given = 0
        for (v in vectors) {
            if (isPhotoFormat(v.input)) continue
            given++
            assertSame(v.name, v.input, PhotoMetadata.withoutLocation(v.input))
            assertEquals(v.name, "same", v.expect)
        }
        assertTrue("$given inputs that are no photo", given >= 25)
        // Empty, one byte, and the prefix of a JPEG, PNG or WebP that is not yet one.
        for (bytes in listOf(
            ByteArray(0), byteArrayOf(0xFF.toByte()), byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
            "RIFF\u0000\u0000\u0000\u0000WEB".toByteArray(Charsets.ISO_8859_1),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A),
        )) assertSame(bytes, PhotoMetadata.withoutLocation(bytes))
    }

    /** The web's own test of this: bytes changed at random never make it throw or fail. */
    @Test
    fun bytesChangedAtRandomNeverMakeItThrowOrFail() {
        val random = Random(0x5EED)
        for (kind in listOf("jpeg", "mpf", "progressive", "png", "webp", "thumb")) {
            val base = vectors.single { it.name == "base: $kind" }.input
            assertNotNull(kind, PhotoMetadata.withoutLocation(base))
            for (round in 0 until 1500) {
                val bytes = base.copyOf()
                for (k in 1 + random.nextInt(4) downTo 1) bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
                assertNotNull("$kind, round $round", PhotoMetadata.withoutLocation(bytes))
            }
            // And cut at every length.
            for (n in 1..base.size) assertNotNull("$kind cut at $n", PhotoMetadata.withoutLocation(base.copyOf(n)))
        }
    }

    @Test
    fun editsAreMadeOrRefused() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertSame(bytes, PhotoMetadata.applyEdits(bytes, mutableListOf()))
        // Given out of order: one overwritten, one dropped.
        val out = PhotoMetadata.applyEdits(bytes, mutableListOf(PhotoMetadata.Edit(5, 7), PhotoMetadata.Edit(1, 2, byteArrayOf(9))))
        assertArrayEquals(byteArrayOf(1, 9, 3, 4, 5, 8), out)
        // What withoutLocation turns into null: edits that overlap, overrun, or change the length of a replacement.
        assertThrows(IllegalStateException::class.java) { PhotoMetadata.applyEdits(bytes, mutableListOf(PhotoMetadata.Edit(1, 4), PhotoMetadata.Edit(3, 5))) }
        assertThrows(IllegalStateException::class.java) { PhotoMetadata.applyEdits(bytes, mutableListOf(PhotoMetadata.Edit(6, 9))) }
        assertThrows(IllegalStateException::class.java) { PhotoMetadata.applyEdits(bytes, mutableListOf(PhotoMetadata.Edit(4, 3))) }
        assertThrows(IllegalStateException::class.java) { PhotoMetadata.applyEdits(bytes, mutableListOf(PhotoMetadata.Edit(1, 3, byteArrayOf(9)))) }
    }
}
