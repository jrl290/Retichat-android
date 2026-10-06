/**
 * Test vectors for PhotoMetadata.withoutLocation, with the web's
 * Retichat-js/lib/photo_metadata.js as the oracle (James, 2026-10-05: a photo
 * leaves without where it was taken; nothing decoded, nothing re-encoded).
 *
 * Every input is run through the web's withoutLocation, and what it answers is
 * written to app/src/test/resources/photo_location_vectors.json:
 *
 *   { name, input: <base64>, expect: "same" | null | { sha256: <hex>, length } }
 *
 * "same" is the identical object back (nothing to change), null a failure
 * inside (the file must not be sent), and an object the cleaned bytes' SHA-256
 * and length. PhotoMetadataTest (JUnit) holds the Kotlin port to every one.
 *
 * The inputs: every scenario the web's three photo tests build
 * (photo_metadata.test.mjs, photo_metadata_decode.test.mjs and
 * attachments_send.test.mjs, from its test_photos.mjs), the cases that take
 * each branch of the oracle (EXIF layouts written by hand, MPF indexes,
 * restart markers, text keywords, RIFF chunks...), and deterministic
 * mutations of them: cuts at the boundaries of every segment and chunk and at
 * a stride, bytes changed with a seeded generator, and u32 fields set to the
 * values that overflow a signed int. Then what no photo is: GIF, HEIC, AVIF,
 * MP4, PDF, random bytes, empty.
 *
 * The web's tests feed Buffers; the app feeds Uint8Arrays, which is what the
 * oracle is given here (Buffer#indexOf is not Uint8Array#indexOf).
 *
 * Run (from anywhere; Retichat-js is the sibling checkout, and Node needs
 * nothing installed):
 *   node scripts/photo_location_vectors.mjs          write the JSON
 *   node scripts/photo_location_vectors.mjs --check  fail when the JSON on disk is not what the oracle gives now
 *
 * The hand-written cases are in photo_location_cases.mjs; how much of the
 * oracle the vectors reach is measured by photo_location_coverage.test.mjs.
 */
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { Buffer } from "node:buffer";
import { mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { withoutLocation } from "../../Retichat-js/lib/photo_metadata.js";
import * as T from "../../Retichat-js/test_photos.mjs";
import { extraCases } from "./photo_location_cases.mjs";

const here = dirname(fileURLToPath(import.meta.url));
export const OUTPUT = resolve(here, "../app/src/test/resources/photo_location_vectors.json");
/** The JSON must stay small enough to keep in the repo and parse in a unit test. */
export const MAX_JSON_BYTES = 3_000_000;

const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

// ── what the oracle answers ─────────────────────────────────────────────────

/** The oracle's answer for `bytes` (a Uint8Array), as it is written. */
export function expectationOf(bytes) {
    const out = withoutLocation(bytes);
    if (out === bytes) return "same";
    if (out === null) return null;
    assert.ok(out instanceof Uint8Array, "bytes back, or the same object, or null");
    return { sha256: sha256(out), length: out.length };
}

// ── the inputs ──────────────────────────────────────────────────────────────

/** A seeded generator: n => an integer in [0, n). */
export function seeded(seed) {
    let s = seed >>> 0;
    return (n) => {
        s = (Math.imul(s, 1103515245) + 12345) >>> 0;
        return (s >>> 8) % n;
    };
}

/** A copy of `bytes` with `writes` made: [offset, number | bytes]. */
export function patched(bytes, ...writes) {
    const out = Buffer.from(bytes);
    for (const [offset, value] of writes) {
        if (typeof value === "number") out[offset] = value;
        else Buffer.from(value).copy(out, offset);
    }
    return out;
}

/** The inputs by name, each once (a second name for the same bytes is dropped). */
class Inputs {
    constructor() {
        this.list = [];
        this.names = new Set();
        this.contents = new Set();
    }

    add(name, bytes) {
        assert.ok(!this.names.has(name), `two inputs named ${name}`);
        this.names.add(name);
        const key = Buffer.from(bytes).toString("base64");
        if (this.contents.has(key)) return false;
        this.contents.add(key);
        this.list.push({ name, input: new Uint8Array(bytes) });
        return true;
    }
}

/** A TIFF block for a JPEG APP1, a PNG eXIf and a WebP EXIF, one input each. */
function exifIn(inputs, name, block) {
    inputs.add(`${name} (JPEG)`, T.jpegWith([T.exifSegment(block), T.comSegment()]));
    inputs.add(`${name} (PNG)`, T.png({ before: [T.pngChunk("eXIf", block)] }));
    inputs.add(`${name} (WebP)`, T.webp([T.riffChunk("EXIF", block)]));
}

/** A TIFF block with the GPS sentinel in it, changed by `f` (the web test's `changed`). */
function changed(f, options = {}) {
    const t = T.tiffBlock(options);
    const b = Buffer.from(t.block);
    f(b, t);
    return b;
}

/** A thumbnail JPEG with its own GPS EXIF and XMP. */
const gpsThumbnail = () => T.jpegWith([T.exifSegment(T.tiffBlock({ exif: false, orientation: 1, gps: T.gpsEntries(T.SENTINEL.thumbGps) }).block), T.xmpSegment()], { base: T.THUMB_JPEG });

function gpsJpegWithThumbnail() {
    const thumbnail = T.jpegWith([T.exifSegment(T.tiffBlock({ exif: false, gps: T.gpsEntries(T.SENTINEL.thumbGps) }).block)], { base: T.THUMB_JPEG });
    return T.jpegWith([T.exifSegment(T.tiffBlock({ thumbnail }).block), T.xmpSegment(), T.iptcSegment()]);
}

/** The scenarios the web's three photo tests build. */
function webScenarios(inputs) {
    const { SENTINEL, TAG } = T;

    // photo_metadata.test.mjs: JPEG, both byte orders, every kind of segment.
    for (const le of [true, false]) {
        const exif = T.tiffBlock({ le });
        const input = T.jpegWith([T.exifSegment(exif.block), T.xmpSegment(), T.extendedXmpSegment(), T.iptcSegment(), T.iccSegment(), T.comSegment(),
            T.goproSegment(), T.c2paSegment(), T.duckySegment(), T.segment(0xE1, "QVCI\0", SENTINEL.xmp), T.adobeSegment()]);
        inputs.add(`web: JPEG, ${le ? "little" : "big"}-endian EXIF, XMP, extended XMP, IPTC, ICC, COM, GoPro, C2PA, Ducky, an unknown APP1, Adobe`, input);
    }
    {
        const base = T.jpegWith([T.exifSegment(T.tiffBlock().block)], { base: T.PROGRESSIVE_JPEG });
        const firstScanEnd = T.jpegSegments(base).segments.find((s) => s.marker === "scan").end;
        inputs.add("web: progressive JPEG, metadata between its scans", Buffer.concat([base.subarray(0, firstScanEnd), T.xmpSegment(), T.iptcSegment(), T.comSegment("between scans"), base.subarray(firstScanEnd)]));
    }
    {
        const thumbnail = gpsThumbnail();
        inputs.add("web: JPEG, big-endian EXIF with a thumbnail that has its own GPS and XMP", T.jpegWith([T.exifSegment(T.tiffBlock({ le: false, thumbnail }).block)]));
        inputs.add("web: the thumbnail's own JPEG", thumbnail);
    }
    inputs.add("web: JPEG with a trailer after EOI (a video, Samsung's)", T.jpegWith([T.exifSegment(T.tiffBlock().block)], { trailer: `\0\0\0\x18ftypmp42 SEFH ${SENTINEL.trailer} SEFT` }));
    {
        const { bytes, gainMap, primary } = T.gainMapJpeg();
        inputs.add("web: MPF gain map, a video between, a trailer after", bytes);
        inputs.add("web: the MPF gain map's own JPEG", gainMap);
        inputs.add("web: the MPF primary alone (its index lists images that are not there)", primary);
    }
    for (const [why, entries] of [
        ["past the end", [{ size: 1, offset: 0 }, { size: 400, offset: 99_999 }]],
        ["inside the photo", [{ size: 1, offset: 0 }, { size: 40, offset: 10 }]],
        ["the photo's own entry not at 0", [{ size: 1, offset: 5 }]],
    ]) {
        inputs.add(`web: MPF index that does not describe the file, ${why}`,
            T.jpegWith([T.exifSegment(T.tiffBlock().block), T.mpfSegment(entries)], { trailer: `\xFF\xD8\xFF ${SENTINEL.trailer}` }));
    }

    // PNG, WebP.
    for (const [le, header] of [[true, ""], [false, ""], [true, "Exif\0\0"]]) {
        const exif = T.tiffBlock({ le });
        inputs.add(`web: PNG, ${le ? "little" : "big"}-endian eXIf${header ? " behind Exif\\0\\0" : ""}, XMP, raw profiles, location text, vendor chunks, a trailer`, T.png({
            before: [
                T.pngChunk("eXIf", Buffer.concat([Buffer.from(header, "latin1"), exif.block])),
                T.textChunk("iTXt", "XML:com.adobe.xmp", T.XMP_PACKET),
                T.textChunk("zTXt", "Raw profile type exif", `\nexif\n  40\n${SENTINEL.rawProfile}`),
                T.textChunk("tEXt", "Raw profile type iptc", `\niptc\n  40\n${SENTINEL.iptc}`),
                T.textChunk("tEXt", "GPSLocation", SENTINEL.location),
                T.textChunk("iTXt", "Location", SENTINEL.location),
                T.textChunk("tEXt", "Comment", "a comment the user wrote"),
                T.pngChunk("tIME", Buffer.from([7, 234, 10, 5, 12, 0, 0])),
                T.pngChunk("pHYs", Buffer.from([0, 0, 0x0B, 0x13, 0, 0, 0x0B, 0x13, 1])),
                T.pngChunk("exIf", T.tiffBlock({ gps: T.gpsEntries(SENTINEL.thumbGps) }).block),
                T.pngChunk("caBX", SENTINEL.c2pa),
                T.pngChunk("prVW", SENTINEL.vendor),
            ],
            trailer: SENTINEL.trailer,
        }));
    }
    for (const header of ["", "Exif\0\0"]) {
        inputs.add(`web: WebP${header ? ", EXIF behind Exif\\0\\0" : ""}, EXIF, XMP and an unknown chunk`,
            T.webp([T.riffChunk("EXIF", Buffer.concat([Buffer.from(header, "latin1"), T.tiffBlock().block])), T.riffChunk("XMP ", T.XMP_PACKET), T.riffChunk("ZZZZ", SENTINEL.vendor)]));
    }
    {
        const bad = Buffer.concat([Buffer.from("XX*\0", "latin1"), T.tiffBlock().block.subarray(4)]);
        inputs.add("web: WebP, an EXIF chunk that cannot be parsed, XMP, bytes after the RIFF",
            Buffer.concat([T.webp([T.riffChunk("EXIF", bad), T.riffChunk("XMP ", T.XMP_PACKET)]), Buffer.from(SENTINEL.trailer)]));
    }

    // An EXIF block that cannot be parsed, in the three formats.
    const unparseable = {
        "no byte order mark": changed((b) => b.write("XX", 0, "latin1")),
        "not 42": changed((b) => b.writeUInt16LE(43, 2)),
        "IFD0 past the end": changed((b) => b.writeUInt32LE(b.length + 100, 4)),
        "IFD0 cut by the end": changed((b, { at }) => b.writeUInt16LE(5000, at.ifd0)),
        "the Exif IFD cut by the end": changed((b, { at }) => b.writeUInt16LE(5000, at.exif)),
        "an Exif IFD pointer that is text": changed((b, { entry }) => b.writeUInt16LE(2, entry.ifd0[TAG.ExifIFD] + 2)),
        "a block of 6 bytes": Buffer.from("II*\0\b\0", "latin1"),
    };
    for (const [why, bad] of Object.entries(unparseable)) exifIn(inputs, `web: unparseable EXIF, ${why}`, bad);

    // A GPS IFD that cannot be read.
    const unreadable = {
        "the GPS IFD cut by the end": changed((b, { at }) => b.writeUInt16LE(5000, at.gps)),
        "a GPS value past the end": changed((b, { entry }) => b.writeUInt32LE(b.length - 4, entry.gps[TAG.GPSLatitude] + 8)),
        "a GPS entry of no type": changed((b, { at }) => b.writeUInt16LE(99, at.gps + 2 + 2)),
        "a GPSInfo pointer that is text": changed((b, { entry }) => b.writeUInt16LE(2, entry.ifd0[TAG.GPSInfo] + 2)),
        "a GPSInfo pointer of two values": changed((b, { entry }) => b.writeUInt32LE(2, entry.ifd0[TAG.GPSInfo] + 4)),
        "a GPSInfo pointer past the end": changed((b, { entry }) => b.writeUInt32LE(b.length + 64, entry.ifd0[TAG.GPSInfo] + 8)),
        "the block cut inside the GPS IFD": T.tiffBlock().block.subarray(0, T.tiffBlock().at.gps + 30),
    };
    for (const [why, bad] of Object.entries(unreadable)) exifIn(inputs, `web: unreadable GPS IFD, ${why}`, bad);

    // GPS a naive stripper left behind, segments a wrong length swallowed.
    const orphan = changed((b, { at, entry }) => {
        const gps = entry.ifd0[TAG.GPSInfo];
        b.copy(b, gps, gps + 12, at.ifd0 + 2 + 12 * 5 + 4);
        b.writeUInt16LE(4, at.ifd0);
    });
    exifIn(inputs, "web: GPS left without its pointer", orphan);
    {
        const exif = T.exifSegment(T.tiffBlock().block);
        const swallowed = T.jpegWith([exif, T.xmpSegment(), T.iptcSegment()]);
        swallowed.writeUInt16BE(exif.length - 2 + T.xmpSegment().length + T.iptcSegment().length, 22);
        inputs.add("web: APP1 length that swallows the XMP and IPTC segments after it", swallowed);
    }

    // XMP and IPTC written into EXIF, an uncompressed thumbnail's strips.
    {
        const strips = Buffer.alloc(24, 0x5A);
        const block = T.tiffBlock({ le: false, strips, ifd0Extra: [
            [TAG.XMLPacket, 7, T.XMP_PACKET],
            [TAG.IPTCNAA, 7, `\x1C\x02\x5A\0\x14${SENTINEL.iptc}`],
            [TAG.ImageResources, 7, `8BIM\x04\x04\0\0\0\0\0\x14${SENTINEL.location}`],
        ] }).block;
        exifIn(inputs, "web: XMP and IPTC in EXIF, strips", block);
    }
    {
        const { block, at } = T.tiffBlock({ thumbnail: T.jpegWith([], { base: T.THUMB_JPEG }) });
        const b = Buffer.from(block);
        b.writeUInt32LE(at.ifd0, at.ifd1 + 2 + 12 * 3);
        inputs.add("web: an IFD chain that loops", T.jpegWith([T.exifSegment(b)]));
    }

    // A JPEG whose structure breaks.
    {
        const gps = T.exifSegment(T.tiffBlock().block);
        const longer = Buffer.from(gps);
        longer.writeUInt16BE(0xFFF0, 2);
        inputs.add("web: JPEG, the EXIF segment runs past the end", Buffer.concat([T.BASE_JPEG.subarray(0, 20), longer]));
        inputs.add("web: JPEG, a segment length under 2", Buffer.concat([T.BASE_JPEG.subarray(0, 20), Buffer.from([0xFF, 0xE1, 0x00, 0x01]), gps, T.imageDataOf(T.BASE_JPEG)]));
        inputs.add("web: JPEG, a file cut inside its EXIF", T.jpegWith([gps]).subarray(0, 20 + 200));
        inputs.add("web: JPEG, bytes that are no segment", T.jpegWith([gps, Buffer.from(`garbage ${SENTINEL.location} `, "latin1")]));
        inputs.add("web: JPEG, a second SOI", T.jpegWith([gps, Buffer.from([0xFF, 0xD8]), T.xmpSegment()]));
        inputs.add("web: JPEG cut in its scan, no EOI", T.jpegWith([T.exifSegment(T.tiffBlock().block), T.xmpSegment()]).subarray(0, -40));
    }

    // A PNG or WebP whose structure breaks.
    {
        const gps = T.pngChunk("eXIf", T.tiffBlock().block);
        const p = T.png({ before: [gps] });
        const ihdrTooLong = Buffer.from(p);
        ihdrTooLong.writeUInt32BE(13 + gps.length, 8);
        inputs.add("web: PNG, IHDR's length runs over eXIf", ihdrTooLong);
        const hugeIhdr = Buffer.from(p);
        hugeIhdr.writeUInt32BE(0x7FFFFFF0, 8);
        inputs.add("web: PNG, a huge IHDR length", hugeIhdr);
        const xmpAfter = T.png({ before: [gps], after: [T.textChunk("iTXt", "XML:com.adobe.xmp", T.XMP_PACKET)] });
        inputs.add("web: PNG cut in its IDAT", xmpAfter.subarray(0, xmpAfter.indexOf("IDAT") + 20));
        const idatTooLong = Buffer.from(xmpAfter);
        idatTooLong.writeUInt32BE(0x00FFFFFF, xmpAfter.indexOf("IDAT") - 4);
        inputs.add("web: PNG, an IDAT length that runs over the XMP after it", idatTooLong);

        const w = T.webp([T.riffChunk("EXIF", T.tiffBlock().block), T.riffChunk("XMP ", T.XMP_PACKET)]);
        const vp8xTooLong = Buffer.from(w);
        vp8xTooLong.writeUInt32LE(10 + T.VP8_CHUNK.length, 16);
        inputs.add("web: WebP, VP8X's size runs over the VP8 chunk", vp8xTooLong);
        inputs.add("web: WebP cut in its VP8 chunk", w.subarray(0, 12 + 18 + 40));
        const vp8TooLong = Buffer.from(w);
        vp8TooLong.writeUInt32LE(0x00FFFFFF, 12 + 18 + 4);
        inputs.add("web: WebP, a VP8 size past the RIFF", vp8TooLong);
    }

    // As given, and nothing to remove.
    inputs.add("web: GIF", Buffer.from("GIF89a\x10\x00\x08\x00\x80\x00\x00", "latin1"));
    inputs.add("web: HEIC", Buffer.concat([Buffer.from([0, 0, 0, 0x18]), Buffer.from("ftypheic\0\0\0\0mif1heic", "latin1")]));
    inputs.add("web: PDF", Buffer.from("%PDF-1.7\n", "latin1"));
    inputs.add("web: bytes that are no format", Buffer.alloc(600, 7));
    inputs.add("web: empty", Buffer.alloc(0));
    inputs.add("web: a JPEG's SOI and nothing else", Buffer.from([0xFF, 0xD8]));
    inputs.add("web: a RIFF that is no WebP", Buffer.from("RIFF\0\0\0\0WAVEfmt ", "latin1"));
    const noGps = T.jpegWith([T.exifSegment(T.tiffBlock({ gps: null }).block), T.iccSegment(), T.isoGainMapSegment(), T.adobeSegment(), T.comSegment()]);
    inputs.add("web: JPEG as it is (base)", T.BASE_JPEG);
    inputs.add("web: progressive JPEG as it is", T.PROGRESSIVE_JPEG);
    inputs.add("web: thumbnail JPEG as it is", T.THUMB_JPEG);
    inputs.add("web: JPEG with EXIF without GPS, ICC, ISO 21496-1, Adobe, COM", noGps);
    inputs.add("web: PNG as it is", T.png());
    inputs.add("web: PNG with eXIf without GPS", T.png({ before: [T.pngChunk("eXIf", T.tiffBlock({ gps: null }).block)] }));
    inputs.add("web: WebP as it is, VP8X", T.webp([]));
    inputs.add("web: WebP with EXIF without GPS", T.webp([T.riffChunk("EXIF", T.tiffBlock({ gps: null }).block)]));
    inputs.add("web: simple WebP, RIFF size 90", Buffer.concat([Buffer.from("RIFF\x5a\0\0\0WEBP", "latin1"), T.VP8_CHUNK]));

    // photo_metadata_decode.test.mjs: the photos Chromium decodes.
    inputs.add("decode: JPEG, little-endian EXIF, XMP, IPTC, ICC, Adobe, COM, GoPro",
        T.jpegWith([T.exifSegment(T.tiffBlock().block), T.xmpSegment(), T.iptcSegment(), T.iccSegment(), T.adobeSegment(), T.comSegment(), T.goproSegment()]));
    inputs.add("decode: JPEG, big-endian EXIF with a thumbnail that has its own GPS, XMP", T.jpegWith([T.exifSegment(T.tiffBlock({ le: false, thumbnail: gpsThumbnail() }).block), T.xmpSegment()]));
    inputs.add("decode: progressive JPEG, EXIF and XMP", T.jpegWith([T.exifSegment(T.tiffBlock().block), T.xmpSegment()], { base: T.PROGRESSIVE_JPEG }));
    inputs.add("decode: PNG, big-endian eXIf, XMP, location text",
        T.png({ before: [T.pngChunk("eXIf", T.tiffBlock({ le: false }).block), T.textChunk("iTXt", "XML:com.adobe.xmp", T.XMP_PACKET), T.textChunk("tEXt", "Location", SENTINEL.location)] }));
    inputs.add("decode: WebP, EXIF and XMP", T.webp([T.riffChunk("EXIF", T.tiffBlock().block), T.riffChunk("XMP ", T.XMP_PACKET)]));

    // attachments_send.test.mjs: a phone's photo.
    inputs.add("send: a phone's JPEG (GPS EXIF, XMP, IPTC), little-endian", T.gpsJpeg());
    inputs.add("send: a phone's JPEG (GPS EXIF, XMP, IPTC), big-endian", T.gpsJpeg({ le: false }));
}

// ── deterministic mutations ─────────────────────────────────────────────────

/** The photos the web test cuts at every length and changes at random. */
function mutationBases() {
    const thumbnail = gpsThumbnail();
    return {
        jpeg: gpsJpegWithThumbnail(),
        mpf: T.gainMapJpeg().bytes,
        progressive: T.jpegWith([T.exifSegment(T.tiffBlock().block)], { base: T.PROGRESSIVE_JPEG }),
        png: T.png({ before: [T.pngChunk("eXIf", T.tiffBlock().block), T.textChunk("iTXt", "XML:com.adobe.xmp", T.XMP_PACKET), T.textChunk("tEXt", "Location", T.SENTINEL.location)] }),
        webp: T.webp([T.riffChunk("EXIF", T.tiffBlock({ le: false }).block), T.riffChunk("XMP ", T.XMP_PACKET)]),
        thumb: T.jpegWith([T.exifSegment(T.tiffBlock({ le: false, thumbnail }).block)]),
    };
}

/** `list` thinned, evenly, to at most `max` entries. */
function thinned(list, max) {
    if (list.length <= max) return list;
    return Array.from({ length: max }, (_, i) => list[Math.floor((i * list.length) / max)]);
}

/** Where a photo's structure begins and ends: the cuts that matter. */
function boundaries(name, bytes) {
    const at = new Set([1, 2, 3, 4, bytes.length - 1]);
    const add = (...positions) => positions.forEach((p) => p > 0 && p < bytes.length && at.add(p));
    if (bytes[0] === 0xFF) {
        // After the FF, after the marker, inside the length, after it, in the middle, one byte short, whole.
        for (const s of T.jpegSegments(bytes).segments) add(s.start + 1, s.start + 2, s.start + 3, s.start + 5, Math.floor((s.start + s.end) / 2), s.end - 1, s.end);
        if (name === "mpf") {
            const m = T.mpfEntries(bytes);
            for (const image of m.images) add(m.base + image.offset - 1, m.base + image.offset, m.base + image.offset + 1, m.base + image.offset + image.size - 1, m.base + image.offset + image.size);
        }
    } else if (bytes[0] === 0x89) {
        let pos = 8;
        while (pos + 12 <= bytes.length) {
            const size = bytes.readUInt32BE(pos);
            add(pos, pos + 1, pos + 4, pos + 7, pos + 8, pos + 9, pos + 8 + size - 1, pos + 8 + size, pos + 8 + size + 3, pos + 12 + size);
            pos += 12 + size;
        }
    } else {
        let pos = 12;
        add(8, 11, 12, 13);
        while (pos + 8 <= bytes.length) {
            const size = bytes.readUInt32LE(pos + 4);
            add(pos, pos + 1, pos + 4, pos + 7, pos + 8, pos + 9, pos + 8 + size - 1, pos + 8 + size, pos + 8 + size + (size & 1));
            pos += 8 + size + (size & 1);
        }
    }
    return thinned([...at].sort((a, b) => a - b), 90);
}

/** Cuts at the boundaries and at a stride, bytes changed at random, u32 fields at their extremes. */
function mutations(inputs, bases) {
    for (const [name, bytes] of Object.entries(bases)) {
        const cuts = new Set(boundaries(name, bytes));
        for (let n = 7; n < bytes.length; n += 97) cuts.add(n);
        for (const n of [...cuts].sort((a, b) => a - b)) inputs.add(`cut ${name} at ${n}`, bytes.subarray(0, n));
    }

    const random = seeded(0x5EED);
    for (const [name, bytes] of Object.entries(bases)) {
        for (let round = 0; round < 40; round++) {
            const b = Buffer.from(bytes);
            const writes = [];
            for (let k = 1 + random(4); k > 0; k--) writes.push([random(b.length), random(256)]);
            for (const [at, value] of writes) b[at] = value;
            inputs.add(`changed ${name} ${round}: ${writes.map(([at, value]) => `${at}=${value}`).join(" ")}`, b);
        }
    }

    // u32 fields set to the values that break a signed int: a byte changed at
    // random rarely lands on a whole field.
    const extremes = [0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0xFFFFFFF0, 0x00010000, 0xFFFF, 0, 1];
    const fieldRandom = seeded(0xF1E1D);
    for (const [name, bytes] of Object.entries(bases)) {
        for (let round = 0; round < 14; round++) {
            const b = Buffer.from(bytes);
            const at = fieldRandom(b.length - 4);
            const value = extremes[fieldRandom(extremes.length)];
            const be = fieldRandom(2) === 0;
            if (be) b.writeUInt32BE(value, at); else b.writeUInt32LE(value, at);
            inputs.add(`u32 ${name} ${round}: ${be ? "BE" : "LE"} ${value.toString(16)} at ${at}`, b);
        }
    }
}

/** What no photo is. */
function nonImages(inputs) {
    const random = seeded(0xB10B);
    const noise = (n) => Buffer.from(Array.from({ length: n }, () => random(256)));
    inputs.add("not an image: GIF87a", Buffer.from("GIF87a\x01\x00\x01\x00\x00\x00\x00;", "latin1"));
    inputs.add("not an image: HEIC with an Exif item and GPS-looking text", Buffer.concat([Buffer.from([0, 0, 0, 0x1C]), Buffer.from("ftypheix\0\0\0\0mif1heic", "latin1"), Buffer.from(`Exif\0\0MM\0*${T.SENTINEL.gps}`, "latin1")]));
    inputs.add("not an image: AVIF", Buffer.concat([Buffer.from([0, 0, 0, 0x20]), Buffer.from("ftypavif\0\0\0\0avifmif1miaf", "latin1"), noise(40)]));
    inputs.add("not an image: MP4 with a location atom", Buffer.concat([Buffer.from("\0\0\0\x18ftypmp42\0\0\0\0mp42isom", "latin1"), Buffer.from(`moov udta \xA9xyz +37.7749-122.4194/ ${T.SENTINEL.trailer}`, "latin1")]));
    inputs.add("not an image: QuickTime movie", Buffer.concat([Buffer.from("\0\0\0\x14ftypqt  \0\0\0\0qt  ", "latin1"), noise(33)]));
    inputs.add("not an image: PDF", Buffer.from(`%PDF-1.4\n1 0 obj <</Title (${T.SENTINEL.location})>> endobj\n%%EOF\n`, "latin1"));
    inputs.add("not an image: a TIFF with GPS (II)", Buffer.concat([Buffer.from("II*\0", "latin1"), T.tiffBlock().block.subarray(4)]));
    inputs.add("not an image: a TIFF with GPS (MM)", T.tiffBlock({ le: false }).block);
    inputs.add("not an image: BMP", Buffer.concat([Buffer.from("BM", "latin1"), noise(60)]));
    inputs.add("not an image: SVG with a location", Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg"><title>${T.SENTINEL.location}</title></svg>`, "utf8"));
    inputs.add("not an image: plain text", Buffer.from("IMG_0001.jpg\n", "utf8"));
    inputs.add("not an image: ZIP", Buffer.concat([Buffer.from("PK\x03\x04", "latin1"), noise(50)]));
    inputs.add("not an image: RIFF AVI", Buffer.from("RIFF\x10\0\0\0AVI LIST\x04\0\0\0hdrl", "latin1"));
    inputs.add("not an image: RIFF too short to name its form", Buffer.from("RIFF\x04\0\0\0WEB", "latin1"));
    inputs.add("not an image: the PNG signature missing a byte", T.png().subarray(0, 7));
    inputs.add("not an image: a PNG signature with one byte changed", patched(T.png(), [4, 0x0E]));
    inputs.add("not an image: FF D8 and a byte that is no marker start", Buffer.from([0xFF, 0xD8, 0x00, 0xFF, 0xD9]));
    inputs.add("not an image: one byte", Buffer.from([0xFF]));
    inputs.add("not an image: two bytes of JPEG", Buffer.from([0xFF, 0xD8]));
    for (let k = 0; k < 6; k++) inputs.add(`not an image: random bytes ${k}`, noise(40 + 97 * k));
    inputs.add("not an image: random bytes behind a JPEG-like prefix", Buffer.concat([Buffer.from([0xFF, 0xD8, 0x00]), noise(80)]));
    inputs.add("not an image: RIFF WEBP signature one byte short", Buffer.from("RIFF\x10\0\0\0WEBP".slice(0, 11), "latin1"));
}

/** Every input, each once (the mutated photos first: a name is kept for the first input with those bytes). */
export function allInputs() {
    const inputs = new Inputs();
    const bases = mutationBases();
    for (const [name, bytes] of Object.entries(bases)) inputs.add(`base: ${name}`, bytes);
    webScenarios(inputs);
    extraCases(inputs, { T, patched });
    mutations(inputs, bases);
    nonImages(inputs);
    return inputs.list;
}

// ── the vectors ─────────────────────────────────────────────────────────────

/** The vectors: every input with what the oracle answers. */
export function buildVectors() {
    return allInputs().map(({ name, input }) => ({ name, input, expect: expectationOf(input) }));
}

/** What only the oracle's own types can show (a Uint8Array is all Kotlin has). */
export function oracleOnlyChecks() {
    for (const value of [null, undefined, "IMG_0001.jpg", new ArrayBuffer(8), [0xFF, 0xD8, 0xFF], 7]) assert.equal(withoutLocation(value), value, "anything that is no Uint8Array comes back");
    class Failing extends Uint8Array {
        indexOf() { throw new Error("boom"); }
    }
    const photo = T.jpegWith([T.exifSegment(T.tiffBlock().block)]);
    const failing = new Failing(photo.length);
    failing.set(photo);
    assert.equal(withoutLocation(failing), null, "a failure inside is null, never an exception");
}

/** The JSON written for `vectors`: one entry a line. */
export function toJson(vectors) {
    const lines = vectors.map((v) => JSON.stringify({ name: v.name, input: Buffer.from(v.input).toString("base64"), expect: v.expect }));
    return `[\n${lines.join(",\n")}\n]\n`;
}

/** Properties of the oracle the vectors are only worth anything with. */
export function checkVectors(vectors) {
    const changedVectors = vectors.filter((v) => v.expect && v.expect !== "same");
    for (const v of vectors) {
        const out = withoutLocation(v.input);
        if (out && out !== v.input) {
            // Cleaned once, nothing is left to clean.
            const again = withoutLocation(out);
            assert.equal(again, out, `${v.name}: the oracle is not idempotent on this vector`);
        }
        if (v.expect === "same") assert.equal(withoutLocation(v.input), v.input);
    }
    return { count: vectors.length, same: vectors.filter((v) => v.expect === "same").length, nulls: vectors.filter((v) => v.expect === null).length, changed: changedVectors.length };
}

function main() {
    oracleOnlyChecks();
    const vectors = buildVectors();
    const summary = checkVectors(vectors);
    // The same vectors twice: the generator is deterministic.
    assert.equal(toJson(vectors), toJson(buildVectors()), "the generator is not deterministic");
    const json = toJson(vectors);
    assert.ok(Buffer.byteLength(json) <= MAX_JSON_BYTES, `${Buffer.byteLength(json)} bytes of JSON is over ${MAX_JSON_BYTES}`);
    if (process.argv.includes("--check")) {
        assert.ok(existsSync(OUTPUT), `${OUTPUT} is missing`);
        assert.equal(readFileSync(OUTPUT, "utf8"), json, `${OUTPUT} is not what the oracle gives now: run scripts/photo_location_vectors.mjs`);
        console.log(`${OUTPUT} is current: ${summary.count} vectors`);
        return;
    }
    mkdirSync(dirname(OUTPUT), { recursive: true });
    writeFileSync(OUTPUT, json);
    console.log(`${summary.count} vectors (${summary.same} same, ${summary.changed} cleaned, ${summary.nulls} null), ${Buffer.byteLength(json)} bytes -> ${OUTPUT}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
