/**
 * Inputs that take each branch of Retichat-js/lib/photo_metadata.js, written
 * by hand: EXIF layouts the web's builders do not make (an Interoperability
 * IFD, pointers of every type, strips, thumbnails of every kind, ranges that
 * overlap), MPF indexes (both byte orders, every way to be wrong), restart
 * markers and fill bytes in a JPEG, PNG text keywords, RIFF chunks.
 * photo_location_vectors.mjs adds them to the web's own scenarios, and
 * photo_location_coverage.test.mjs measures how much of the oracle they reach.
 */
import { Buffer } from "node:buffer";

const BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, UNDEFINED = 7, IFD_TYPE = 13;
const SIZE = [0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4];

const TAGS = {
    InteropIndex: 0x0001, Compression: 0x0103, Make: 0x010F, Orientation: 0x0112, StripOffsets: 0x0111, StripByteCounts: 0x0117,
    XMLPacket: 0x02BC, IPTCNAA: 0x83BB, ImageResources: 0x8649, ExifIFD: 0x8769, GPSInfo: 0x8825, InteropIFD: 0xA005,
    ThumbnailOffset: 0x0201, ThumbnailLength: 0x0202, ExposureTime: 0x829A,
};

/**
 * A TIFF block from named IFDs in file order, each followed by its out-of-line
 * values, then the thumbnail, then `tail` zero bytes. An entry is
 * [tag, type, value]; value is
 *   an array of numbers (BYTE, SHORT, LONG...), of [numerator, denominator]
 *     (RATIONAL), or of { ref, plus } (where a named IFD or the "thumbnail"
 *     lies, plus `plus`, as a number);
 *   a string (ASCII with its NUL, any other type as its bytes);
 *   { ref, plus, short }: a pointer to a named part, as a LONG, or as a SHORT
 *     (the first two bytes of the value field) when `short`;
 *   { raw, count }: the 4-byte value field as it is;
 *   { at, count }: an offset to write as it is.
 * An IFD's `next` is a name, or a number; `count` overrides an IFD's entry
 * count. Returns { block, at: {name: offset}, entry: {name: {tag: offset}} }.
 */
function buildTiff({ le = true, ifds, thumbnail = null, tail = 0, count = {} }) {
    const put = (out, v, n) => { for (let i = 0; i < n; i++) out.push((v >>> (8 * (le ? i : n - 1 - i))) & 0xFF); };
    const encode = (type, value) => {
        if (typeof value === "string") {
            const bytes = Buffer.from(type === ASCII ? `${value}\0` : value, "latin1");
            return { count: bytes.length, size: bytes.length, bytes: () => bytes };
        }
        const unit = type === RATIONAL ? 8 : SIZE[type];
        return {
            count: value.length,
            size: value.length * unit,
            bytes: (at) => {
                const out = [];
                for (const v of value) {
                    if (type === RATIONAL) { put(out, v[0], 4); put(out, v[1], 4); } else put(out, typeof v === "number" ? v : at[v.ref] + (v.plus ?? 0), unit);
                }
                return Buffer.from(out);
            },
        };
    };
    const at = {};
    const entry = {};
    let pos = 8;
    const laid = ifds.map((ifd) => {
        at[ifd.name] = pos;
        entry[ifd.name] = {};
        pos += 2 + 12 * ifd.entries.length + 4;
        const encoded = ifd.entries.map(([tag, type, value]) => {
            if (!Array.isArray(value) && typeof value === "object") return { tag, type, field: value };
            const e = encode(type, value);
            const out = { tag, type, count: e.count, bytes: e.bytes };
            if (e.size > 4) {
                out.valueAt = pos;
                pos += e.size + (e.size & 1);
            }
            return out;
        });
        return { ifd, encoded };
    });
    if (thumbnail) {
        at.thumbnail = pos;
        pos += thumbnail.length;
    }
    pos += tail;
    const block = Buffer.alloc(pos);
    const u16 = (o, v) => (le ? block.writeUInt16LE(v, o) : block.writeUInt16BE(v, o));
    const u32 = (o, v) => (le ? block.writeUInt32LE(v, o) : block.writeUInt32BE(v, o));
    block.write(le ? "II" : "MM", 0, "latin1");
    u16(2, 42);
    u32(4, at[ifds[0].name]);
    for (const { ifd, encoded } of laid) {
        const base = at[ifd.name];
        u16(base, count[ifd.name] ?? encoded.length);
        encoded.forEach((e, k) => {
            const p = base + 2 + 12 * k;
            entry[ifd.name][e.tag] = p;
            u16(p, e.tag);
            u16(p + 2, e.type);
            if (e.field !== undefined) {
                const f = e.field;
                if (f.raw !== undefined) {
                    u32(p + 4, f.count ?? 1);
                    Buffer.from(f.raw).copy(block, p + 8);
                } else if (f.at !== undefined) {
                    u32(p + 4, f.count ?? 1);
                    u32(p + 8, f.at);
                } else {
                    u32(p + 4, 1);
                    const target = at[f.ref] + (f.plus ?? 0);
                    if (f.short) u16(p + 8, target); else u32(p + 8, target);
                }
                return;
            }
            u32(p + 4, e.count);
            const bytes = e.bytes(at);
            if (e.valueAt !== undefined) {
                u32(p + 8, e.valueAt);
                bytes.copy(block, e.valueAt);
            } else bytes.copy(block, p + 8);
        });
        const next = typeof ifd.next === "number" ? ifd.next : ifd.next ? at[ifd.next] : 0;
        u32(base + 2 + 12 * encoded.length, next);
    }
    if (thumbnail) Buffer.from(thumbnail).copy(block, at.thumbnail);
    return { block, at, entry, le };
}

export function extraCases(inputs, helpers) {
    const { T, patched } = helpers;
    const context = { inputs, T, patched, SENTINEL: T.SENTINEL };
    exifCases(context);
    jpegCases(context);
    mpfCases(context);
    pngCases(context);
    webpCases(context);
}

// ── EXIF ────────────────────────────────────────────────────────────────────

/** A thumbnail JPEG with GPS and XMP of its own (the web test's). */
function gpsThumbnail(T) {
    return T.jpegWith([T.exifSegment(T.tiffBlock({ exif: false, orientation: 1, gps: T.gpsEntries(T.SENTINEL.thumbGps) }).block), T.xmpSegment()], { base: T.THUMB_JPEG });
}

function exifCases({ inputs, T, SENTINEL }) {
    const gps = (sentinel = SENTINEL.gps) => T.gpsEntries(sentinel);
    const jpegExif = (name, block) => inputs.add(name, T.jpegWith([T.exifSegment(block), T.comSegment()]));

    for (const le of [true, false]) {
        const order = le ? "little" : "big";
        const name = (what) => `exif, ${order}-endian: ${what}`;
        const tiff = (ifds, options = {}) => buildTiff({ le, ifds, ...options }).block;
        const orientation = [TAGS.Orientation, SHORT, [6]];
        const toGps = [TAGS.GPSInfo, LONG, { ref: "gps" }];
        const toExif = [TAGS.ExifIFD, LONG, { ref: "exif" }];
        const ifd0 = (...more) => ({ name: "ifd0", entries: [orientation, toExif, toGps, ...more] });
        const exif = (...more) => ({ name: "exif", entries: [[TAGS.ExposureTime, RATIONAL, [[1, 120]]], ...more] });
        const gpsIfd = { name: "gps", entries: gps() };
        const interop = { name: "interop", entries: [[TAGS.InteropIndex, ASCII, "R98"]] };
        const withGps = (...more) => ({ name: "ifd0", entries: [orientation, ...more, toGps] });

        // The IFDs the block reaches: Exif, Interoperability, the chain after IFD0.
        jpegExif(name("an Interoperability IFD under the Exif IFD"), tiff([ifd0(), exif([TAGS.InteropIFD, LONG, { ref: "interop" }]), interop, gpsIfd]));
        jpegExif(name("an Interoperability pointer that is text"), tiff([ifd0(), exif([TAGS.InteropIFD, ASCII, "abc"]), gpsIfd]));
        jpegExif(name("pointers that are not followed: Interoperability in IFD0, Exif in the Exif and Interoperability IFDs"), tiff([
            ifd0([TAGS.InteropIFD, LONG, { ref: "interop" }]),
            exif([TAGS.InteropIFD, LONG, { ref: "interop" }], [TAGS.ExifIFD, LONG, { ref: "exif" }]),
            { name: "interop", entries: [[TAGS.InteropIndex, ASCII, "R98"], [TAGS.ExifIFD, LONG, { ref: "ifd0" }], [TAGS.InteropIFD, LONG, { ref: "exif" }]] },
            gpsIfd]));
        jpegExif(name("the Exif pointer points at IFD0 again"), tiff([{ name: "ifd0", entries: [orientation, [TAGS.ExifIFD, LONG, { ref: "ifd0" }], toGps] }, gpsIfd]));
        // Where a pointer to an IFD can point: outside the block (passed over), or cut by its end.
        const interopAt = (at) => tiff([ifd0(), exif([TAGS.InteropIFD, LONG, { at }]), gpsIfd]);
        const exifAt = (at) => tiff([withGps([TAGS.ExifIFD, LONG, { at }]), gpsIfd]);
        for (const [why, offset] of [["past the end", () => 0x7000], ["before byte 8", () => 4], ["at 0", () => 0], ["at 7", () => 7], ["at 0xFFFFFFFF", () => 0xFFFFFFFF],
            ["at the last byte", (size) => size - 1], ["at the last byte but one", (size) => size - 2], ["at the last byte but 13", (size) => size - 13]]) {
            jpegExif(name(`the Interoperability pointer ${why}`), interopAt(offset(interopAt(0).length)));
            jpegExif(name(`the Exif pointer ${why}`), exifAt(offset(exifAt(0).length)));
        }
        // IFD1 and the IFDs after it follow Exif pointers too; the thumbnail tags mean nothing in IFD0.
        jpegExif(name("an Exif IFD reached from IFD1 and from a later IFD of the chain"), tiff([
            { name: "ifd0", entries: [orientation, toGps], next: "ifd1" }, gpsIfd,
            { name: "ifd1", entries: [[TAGS.Compression, SHORT, [6]], [TAGS.ExifIFD, LONG, { ref: "exif1" }]], next: "ifd2" },
            { name: "exif1", entries: [[TAGS.ExposureTime, RATIONAL, [[1, 60]]]] },
            { name: "ifd2", entries: [[TAGS.ExifIFD, LONG, { ref: "exif2" }], [TAGS.GPSInfo, LONG, { ref: "gps2" }]] },
            { name: "exif2", entries: [[TAGS.ExposureTime, RATIONAL, [[1, 30]]], [TAGS.GPSInfo, LONG, { ref: "gps2" }]] },
            { name: "gps2", entries: gps(SENTINEL.location) }]));
        jpegExif(name("thumbnail tags in IFD0, strips in IFD0"), tiff([{ name: "ifd0", entries: [orientation, toGps, [TAGS.ThumbnailOffset, LONG, [8]], [TAGS.ThumbnailLength, LONG, [20]], [TAGS.StripOffsets, LONG, [8]], [TAGS.StripByteCounts, LONG, [20]]] }, gpsIfd]));
        {
            const link = (n, next) => ({ name: `ifd${n}`, entries: [orientation, [TAGS.GPSInfo, LONG, { ref: `gps${n}` }]], next });
            const gpsN = (n, sentinel) => ({ name: `gps${n}`, entries: gps(sentinel) });
            jpegExif(name("a chain of five IFDs, GPS in each"), tiff([
                { name: "ifd0", entries: [orientation, [TAGS.GPSInfo, LONG, { ref: "gps0" }]], next: "ifd1" }, gpsN(0, SENTINEL.gps),
                link(1, "ifd2"), gpsN(1, SENTINEL.thumbGps), link(2, "ifd3"), gpsN(2, SENTINEL.location), link(3, "ifd4"), gpsN(3, SENTINEL.xmp), link(4, 0), gpsN(4, SENTINEL.iptc)]));
        }
        jpegExif(name("IFD0 with no entries"), tiff([{ name: "ifd0", entries: [] }]));
        jpegExif(name("IFD0 with only GPSInfo"), tiff([{ name: "ifd0", entries: [toGps] }, gpsIfd]));
        jpegExif(name("two GPSInfo entries, Orientation and an XMLPacket"), tiff([{ name: "ifd0", entries: [toGps, toGps, orientation, [TAGS.XMLPacket, UNDEFINED, T.XMP_PACKET]] }, gpsIfd]));

        // Pointers of every type: SHORT and IFD are pointers like LONG, any other type is none.
        for (const [type, label] of [[SHORT, "SHORT"], [IFD_TYPE, "IFD"], [BYTE, "BYTE"], [RATIONAL, "RATIONAL"], [0, "0"], [14, "14"]]) {
            const pointer = (ref) => (type === SHORT ? { ref, short: true } : { ref });
            jpegExif(name(`a GPSInfo pointer of type ${label}`), tiff([{ name: "ifd0", entries: [orientation, [TAGS.GPSInfo, type, pointer("gps")]] }, gpsIfd]));
            jpegExif(name(`an Exif pointer of type ${label}`), tiff([{ name: "ifd0", entries: [orientation, [TAGS.ExifIFD, type, pointer("exif")], toGps] }, exif(), gpsIfd]));
        }
        jpegExif(name("a GPSInfo pointer of two values"), tiff([{ name: "ifd0", entries: [orientation, [TAGS.GPSInfo, LONG, { at: 100, count: 2 }]] }, gpsIfd]));

        // The values the entries point to.
        {
            const values = [];
            for (let type = 1; type <= 13; type++) {
                values.push([0x9100 + type, type, type === RATIONAL || type === 10 ? [[1, 2], [3, 4], [5, 6]] : type === ASCII ? "a value out of line" : [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]]);
            }
            jpegExif(name("an out-of-line value of every type"), tiff([withGps(...values), gpsIfd]));
            const odd = [
                [0x9201, LONG, { at: 0, count: 5 }], [0x9202, LONG, { at: 7, count: 5 }], [0x9203, LONG, { at: 0x7000, count: 5 }], [0x9204, LONG, { at: 0xFFFFFFFC, count: 5 }],
                [0x9205, RATIONAL, { at: 8, count: 0xFFFFFFFF }], [0x9206, 0, { at: 20, count: 8 }], [0x9207, 14, { at: 20, count: 8 }], [0x9208, 99, { at: 20, count: 8 }],
                [0x9209, BYTE, { at: 20, count: 4 }], [0x920A, BYTE, { at: 20, count: 0 }], [0x920B, SHORT, { at: 20, count: 2 }], [0x920C, LONG, { at: 20, count: 1 }],
            ];
            jpegExif(name("values that are not where they can be, and sizes of 4 and under"), tiff([withGps(...odd), gpsIfd]));
        }
        for (const [why, entry] of [
            ["an XMLPacket in the block", [TAGS.XMLPacket, UNDEFINED, T.XMP_PACKET]],
            ["an XMLPacket of 3 bytes, inline", [TAGS.XMLPacket, BYTE, "abc"]],
            ["an XMLPacket past the end", [TAGS.XMLPacket, UNDEFINED, { at: 0x7000, count: 300 }]],
            ["an XMLPacket before byte 8", [TAGS.XMLPacket, UNDEFINED, { at: 3, count: 300 }]],
            ["an XMLPacket of type 0", [TAGS.XMLPacket, 0, { at: 100, count: 300 }]],
            ["an IPTC-NAA in the block", [TAGS.IPTCNAA, UNDEFINED, `\x1C\x02\x5A\0\x14${SENTINEL.iptc}`]],
            ["Photoshop ImageResources in the block", [TAGS.ImageResources, UNDEFINED, `8BIM\x04\x04\0\0\0\0\0\x14${SENTINEL.location}`]],
        ]) jpegExif(name(why), tiff([{ name: "ifd0", entries: [orientation, entry, toGps] }, gpsIfd]));
        for (const [why, entries, count] of [
            ["no entries", [], {}],
            ["one inline entry", [[0x0001, ASCII, "N"]], {}],
            ["an entry of every type", Array.from({ length: 15 }, (_, type) => [type, type, type === 0 ? [0] : type === RATIONAL || type === 10 ? [[1, 2]] : type === ASCII ? "text value" : type === UNDEFINED ? "bytes out of line" : [1, 2, 3, 4, 5]]), {}],
            ["a value before byte 8", [[0x0002, RATIONAL, { at: 3, count: 3 }], [0x0004, RATIONAL, { at: 7, count: 3 }]], {}],
            ["a count past the end", gps(), { gps: 3000 }],
            ["a count of 65535", gps(), { gps: 65535 }],
            ["a count of zero over entries", gps(), { gps: 0 }],
        ]) jpegExif(name(`a GPS IFD with ${why}`), tiff([withGps(), { name: "gps", entries }], { count }));

        // Bytes no IFD references go, GPS or not; an IFD cut by the end of the block.
        {
            const quiet = tiff([{ name: "ifd0", entries: [orientation] }], { tail: 40 });
            quiet.fill(0x5A, quiet.length - 40);
            jpegExif(name("bytes no IFD references, and no GPS"), quiet);
        }
        jpegExif(name("IFD0's last entry cut by the end"), tiff([{ name: "ifd0", entries: [orientation, [TAGS.Make, ASCII, "A camera"]] }]).subarray(0, 8 + 2 + 12 + 6));

        // The cleaning leaves a block that does not parse, or one with GPS in it: the EXIF goes whole.
        {
            // A GPS value that lies over IFD0's Exif entry, once that has moved up: its type and count are zeros.
            const gpsOver = (at) => ({ name: "gps", entries: [[0x0002, RATIONAL, { at, count: 3 }]] });
            jpegExif(name("a GPS value over the Exif entry of IFD0: after the cleaning the Exif pointer is none"), tiff([{ name: "ifd0", entries: [toGps, toExif] }, exif(), gpsOver(12)]));
            const inIfd1 = (at) => tiff([{ name: "ifd0", entries: [orientation], next: "ifd1" }, { name: "ifd1", entries: [toGps, toExif] }, exif(), gpsOver(at)]);
            const ifd1 = buildTiff({ le, ifds: [{ name: "ifd0", entries: [orientation], next: "ifd1" }, { name: "ifd1", entries: [toGps, toExif] }, exif(), gpsOver(0)] }).at.ifd1;
            jpegExif(name("a GPS value over the Exif entry of IFD1"), inIfd1(ifd1 + 4));
        }
        {
            // GPS that shows only once IFD0 is cleaned: the count of an Exif IFD J lives in a word of IFD0 that moves
            // up when IFD0's GPSInfo entry goes, and J's entries beyond its old count hold a GPSInfo entry.
            const block = Buffer.alloc(240);
            const u16 = (o, v) => (le ? block.writeUInt16LE(v, o) : block.writeUInt16BE(v, o));
            const u32 = (o, v) => (le ? block.writeUInt32LE(v, o) : block.writeUInt32BE(v, o));
            block.write(le ? "II" : "MM", 0, "latin1");
            u16(2, 42);
            u32(4, 8);
            u16(8, 7);                                                   // IFD0, 7 entries at 10, 22, 34, ...
            const put = (at, tag, type, count, value) => { u16(at, tag); u16(at + 2, type); u32(at + 4, count); u32(at + 8, value); };
            put(10, TAGS.GPSInfo, LONG, 1, 0);
            put(22, TAGS.ExifIFD, SHORT, 1, 0);
            u16(30, 78);                                                 // the Exif IFD J at 78
            put(34, 0x9000, UNDEFINED, 240 - 98, 98);                    // a value that keeps what lies after IFD0
            u16(78, 0);                                                  // J's count word, the low word of the 6th entry's value: 0 now
            u16(90, 4);                                                  // and 4, the 7th entry's, once the entries move up
            put(116, TAGS.GPSInfo, LONG, 1, 0);                          // J's 4th entry: GPSInfo, beyond what J holds now
            jpegExif(name("GPS that shows once IFD0 is cleaned (a count that moves)"), block);
        }

        // Strips.
        const strips = Buffer.alloc(40, 0x5A);
        const stripCases = {
            "two LONG strips, out of line": [[TAGS.StripOffsets, LONG, [{ ref: "thumbnail" }, { ref: "thumbnail", plus: 20 }]], [TAGS.StripByteCounts, LONG, [20, 20]]],
            "one SHORT strip, inline": [[TAGS.StripOffsets, SHORT, [{ ref: "thumbnail" }]], [TAGS.StripByteCounts, SHORT, [40]]],
            "two SHORT strips, inline": [[TAGS.StripOffsets, SHORT, [{ ref: "thumbnail" }, { ref: "thumbnail", plus: 20 }]], [TAGS.StripByteCounts, SHORT, [20, 20]]],
            "three SHORT strips, out of line": [[TAGS.StripOffsets, SHORT, [{ ref: "thumbnail" }, { ref: "thumbnail", plus: 10 }, { ref: "thumbnail", plus: 20 }]], [TAGS.StripByteCounts, SHORT, [10, 10, 20]]],
            "strips and counts that do not match": [[TAGS.StripOffsets, LONG, [{ ref: "thumbnail" }, { ref: "thumbnail", plus: 20 }]], [TAGS.StripByteCounts, LONG, [40]]],
            "offsets of a type that is no number": [[TAGS.StripOffsets, BYTE, [1, 2, 3, 4, 5, 6]], [TAGS.StripByteCounts, BYTE, [1, 2, 3, 4, 5, 6]]],
            "offsets only": [[TAGS.StripOffsets, LONG, [{ ref: "thumbnail" }]]],
            "counts only": [[TAGS.StripByteCounts, LONG, [40]]],
            "a count past the end": [[TAGS.StripOffsets, LONG, { at: 20, count: 0xFFFFFF }], [TAGS.StripByteCounts, LONG, { at: 20, count: 0xFFFFFF }]],
            "a count the block holds, values past its end": [[TAGS.StripOffsets, LONG, { at: 0x7000, count: 3 }], [TAGS.StripByteCounts, LONG, { at: 0x7000, count: 3 }]],
            "a strip before byte 8 and one past the end": [[TAGS.StripOffsets, LONG, [3, { ref: "thumbnail", plus: 30 }]], [TAGS.StripByteCounts, LONG, [20, 99999]]],
            "no strips": [[TAGS.StripOffsets, LONG, { at: 20, count: 0 }], [TAGS.StripByteCounts, LONG, { at: 20, count: 0 }]],
        };
        for (const [why, entries] of Object.entries(stripCases)) {
            jpegExif(name(`strips: ${why}`), tiff([{ ...withGps(), next: "ifd1" }, gpsIfd, { name: "ifd1", entries: [[TAGS.Compression, SHORT, [1]], ...entries] }], { thumbnail: strips }));
        }

        // The thumbnail: its length as a SHORT or a LONG, a JPEG or not, where it lies.
        const dirty = gpsThumbnail(T);
        const clean = T.THUMB_JPEG;
        const filler = (n) => Buffer.alloc(n, 0x5A);
        const thumbnailCase = (why, { thumbnail = dirty, length = thumbnail.length, lengthType = LONG, offset = { ref: "thumbnail" }, offsetType = LONG, extra = [], tail = 0, gpsInMain = true }) => {
            const entries = [[TAGS.Compression, SHORT, [6]]];
            if (offset) entries.push([TAGS.ThumbnailOffset, offsetType, offset]);
            if (length !== null) entries.push([TAGS.ThumbnailLength, lengthType, [length]]);
            const main = gpsInMain ? [{ ...withGps(), next: "ifd1" }, gpsIfd] : [{ name: "ifd0", entries: [orientation], next: "ifd1" }];
            jpegExif(name(`thumbnail: ${why}`), tiff([...main, { name: "ifd1", entries: [...entries, ...extra] }], { tail, thumbnail }));
        };
        thumbnailCase("a JPEG with GPS and XMP of its own", {});
        thumbnailCase("the same, its offset and length SHORT", { lengthType: SHORT, offsetType: SHORT, offset: { ref: "thumbnail", short: true } });
        thumbnailCase("the same, its length SHORT, its offset LONG", { lengthType: SHORT });
        thumbnailCase("a clean JPEG, GPS in the main EXIF", { thumbnail: clean });
        thumbnailCase("a JPEG with GPS and XMP of its own, no GPS in the main EXIF", { gpsInMain: false });
        thumbnailCase("a clean JPEG, no GPS in the main EXIF", { thumbnail: clean, gpsInMain: false });
        thumbnailCase("not a JPEG", { thumbnail: filler(30) });
        thumbnailCase("FF and then not D8", { thumbnail: Buffer.concat([Buffer.from([0xFF, 0xD9]), filler(30)]) });
        thumbnailCase("a byte that is no FF and then D8", { thumbnail: Buffer.concat([Buffer.from([0x00, 0xD8]), filler(30)]) });
        thumbnailCase("no length entry", { length: null });
        thumbnailCase("a length of 0", { length: 0 });
        thumbnailCase("a length of 1, the one byte the last of the block (FF, and no D8 after it)", { thumbnail: Buffer.from([0xFF]) });
        thumbnailCase("a length past the end", { length: 99999 });
        thumbnailCase("an offset before byte 8", { offset: { at: 3 }, length: 20 });
        thumbnailCase("no offset entry", { offset: null, length: 20 });
        thumbnailCase("two length entries", { extra: [[TAGS.ThumbnailLength, LONG, [dirty.length]]] });
        thumbnailCase("a length that cuts the JPEG", { length: 150 });
        thumbnailCase("a JPEG and bytes after it that nothing references", { tail: 24 });
        thumbnailCase("a JPEG with a trailer after its EOI", { thumbnail: Buffer.concat([dirty, Buffer.from(`trailing ${SENTINEL.trailer}`)]) });
        thumbnailCase("a JPEG with a thumbnail of its own", { thumbnail: T.jpegWith([T.exifSegment(T.tiffBlock({ thumbnail: dirty }).block)], { base: clean }) });
        {
            let deep = dirty;
            const levels = [];
            for (let level = 0; level < 5; level++) {
                deep = T.jpegWith([T.exifSegment(T.tiffBlock({ thumbnail: deep, gps: gps(`${SENTINEL.thumbGps}-${level}`) }).block), T.xmpSegment()], { base: clean });
                levels.push(deep);
            }
            for (const n of [1, 2, 4]) thumbnailCase(`thumbnails ${n + 1} levels deep`, { thumbnail: levels[n] });
        }
    }
}

// ── JPEG ────────────────────────────────────────────────────────────────────

function jpegCases({ inputs, T, SENTINEL }) {
    const segments = T.jpegSegments(T.BASE_JPEG).segments;
    const sos = segments.find((s) => s.marker === 0xDA);
    const scan = segments.find((s) => s.marker === "scan");
    const SOI = Buffer.from([0xFF, 0xD8]);
    const JFIF = T.BASE_JPEG.subarray(2, 20);
    const TABLES = T.BASE_JPEG.subarray(20, sos.start);
    const SOS = T.BASE_JPEG.subarray(sos.start, sos.end);
    const SCAN = T.BASE_JPEG.subarray(scan.start, scan.end);
    const EOI = Buffer.from([0xFF, 0xD9]);
    const exif = T.exifSegment(T.tiffBlock().block);
    const raw = (...parts) => Buffer.concat(parts.map((p) => (typeof p === "string" ? Buffer.from(p, "latin1") : p)));
    const jpeg = (name, ...parts) => inputs.add(`jpeg: ${name}`, raw(SOI, JFIF, ...parts));
    const rest = () => raw(TABLES, SOS, SCAN, EOI);
    const app = (n, id, body = "") => T.segment(0xE0 + n, id, body);

    // Fill bytes before markers, and a file that ends in them or in a marker.
    jpeg("fill bytes before the EXIF segment", Buffer.from([0xFF, 0xFF, 0xFF]), exif, rest());
    jpeg("fill bytes before SOS and EOI", exif, TABLES, Buffer.from([0xFF, 0xFF]), SOS, SCAN, Buffer.from([0xFF, 0xFF, 0xFF]), EOI);
    jpeg("a lone FF at the end of the file", exif, TABLES, SOS, SCAN, Buffer.from([0xFF]));
    jpeg("a marker with no length at the end of the file", exif, TABLES, Buffer.from([0xFF, 0xE1]));
    jpeg("a marker and one byte of its length at the end of the file", exif, TABLES, Buffer.from([0xFF, 0xE1, 0x00]));
    jpeg("FF FF FF at the end of the file", exif, TABLES, Buffer.from([0xFF, 0xFF, 0xFF]));
    jpeg("FF 00 where a marker should be", exif, Buffer.from([0xFF, 0x00]), `stray ${SENTINEL.location}`, rest());
    jpeg("FF 00 and then nothing", exif, TABLES, Buffer.from([0xFF, 0x00]));
    // Markers without a length, and reserved markers.
    jpeg("TEM and restart markers between segments", exif, Buffer.from([0xFF, 0x01, 0xFF, 0xD0, 0xFF, 0xD7]), T.xmpSegment(), rest());
    jpeg("reserved markers 02 and BF, and JPG (C8), with lengths", exif, T.segment(0x02, "reserved"), T.segment(0xBF, `reserved ${SENTINEL.location}`), T.segment(0xC8, "jpg extension"), rest());
    jpeg("JPGn markers FD and F0 and a COM: a decoder keeps what it can skip", exif, T.segment(0xFD, "JPGn"), T.segment(0xF0, "JPG0"), T.comSegment("kept"), rest());
    jpeg("DRI and a scan with restart markers, fill bytes and stuffed FFs in it", exif, T.segment(0xDD, Buffer.from([0, 8])), TABLES, SOS,
        raw(SCAN.subarray(0, 20), Buffer.from([0xFF, 0x00, 0xFF, 0xD0]), SCAN.subarray(20, 40), Buffer.from([0xFF, 0xFF, 0xD3, 0xFF, 0x00]), SCAN.subarray(40)), EOI);
    jpeg("a scan that ends in an FF at the end of the file", exif, TABLES, SOS, SCAN, Buffer.from([0xFF]));
    jpeg("a scan that ends in FF FF FF at the end of the file", exif, TABLES, SOS, SCAN, Buffer.from([0xFF, 0xFF, 0xFF]));
    jpeg("a scan that runs to the end of the file", exif, TABLES, SOS, SCAN);
    jpeg("a scan with a second SOI in it", exif, TABLES, SOS, SCAN.subarray(0, 30), Buffer.from([0xFF, 0xD8]), T.xmpSegment(), SCAN.subarray(30), EOI);
    jpeg("a scan with a segment in it", exif, TABLES, SOS, SCAN.subarray(0, 30), T.xmpSegment(), SCAN.subarray(30), EOI);
    jpeg("EOI straight after the APP0, and a trailer", EOI, `after ${SENTINEL.trailer}`);
    jpeg("no scan and no EOI", exif, TABLES);
    jpeg("two scans, metadata between them", exif, TABLES, SOS, SCAN, T.xmpSegment(), SOS, SCAN, EOI);
    // Garbage between segments and at the end.
    jpeg("garbage up to the next marker", exif, `garbage ${SENTINEL.location}`, rest());
    jpeg("garbage with stuffed FFs in it, up to the next marker", exif, "garbage ", Buffer.from([0xFF, 0x00]), " more ", Buffer.from([0xFF, 0x00, 0xFF, 0x00]), " and more", rest());
    jpeg("garbage that runs to the end of the file", exif, TABLES, `garbage ${SENTINEL.location}`);
    jpeg("garbage and then an FF at the end of the file", exif, TABLES, "garbage", Buffer.from([0xFF]));
    jpeg("garbage and then FF FF FF at the end of the file", exif, TABLES, "garbage", Buffer.from([0xFF, 0xFF, 0xFF]));
    jpeg("garbage ending in FF 00 at the end of the file", exif, TABLES, "garbage", Buffer.from([0xFF, 0x00]));
    // APPn segments.
    jpeg("every APPn there is, with the ones a decoder needs among them", T.segment(0xE0, "JFXX\0", "thumbnail"), app(1, "Exif\0\0"), app(2, "FPXR\0", "flashpix"), T.iccSegment(), T.isoGainMapSegment(),
        app(3, "Meta\0"), app(4, "Qualcomm"), app(5, "RMETA"), T.goproSegment(), app(7, "Ocean"), app(8, "SPIFF"), app(9, "Unknown"), app(10, "Comment"), T.c2paSegment(), T.duckySegment(), T.iptcSegment(),
        app(14, "Other"), app(15, "Picture"), T.adobeSegment(), rest());
    jpeg("an APP14 that is not Adobe, and an APP0 that is not JFIF", T.segment(0xEE, "Ocean", "x"), T.segment(0xE0, "JFXX", "x"), rest());
    jpeg("a second JFIF APP0", JFIF, rest());
    jpeg("APP2 with a name shorter than any it could have", T.segment(0xE2, "MP"), T.segment(0xE2), T.segment(0xE2, "ICC"), rest());
    jpeg("APP1 that is Exif and nothing else", T.segment(0xE1, "Exif\0"), rest());
    jpeg("APP1 that is Exif and a byte more", T.segment(0xE1, "Exif\0\0"), rest());
    jpeg("APP1 with a name shorter than Exif", T.segment(0xE1, "Exi"), T.segment(0xE1), rest());
    jpeg("APP1 that is Exif with a block of 7 bytes", T.segment(0xE1, "Exif\0\0", "II*\0\b\0\0"), rest());
    jpeg("APP1 whose TIFF block is no TIFF", T.segment(0xE1, "Exif\0\0", `not a TIFF block ${SENTINEL.gps}`), rest());
    jpeg("APP1 Exif twice, each with GPS", exif, T.exifSegment(T.tiffBlock({ le: false }).block), rest());
    jpeg("a segment whose length is 2: no payload", Buffer.from([0xFF, 0xE1, 0x00, 0x02]), rest());
    jpeg("a COM segment with a location in it, and an empty COM", T.comSegment(`taken in ${SENTINEL.location}`), T.segment(0xFE), rest());
    inputs.add("jpeg: SOI and fill bytes, then EOI", Buffer.from([0xFF, 0xD8, 0xFF, 0xFF, 0xFF, 0xFF, 0xD9]));
    inputs.add("jpeg: SOI and fill bytes at the end", Buffer.from([0xFF, 0xD8, 0xFF, 0xFF, 0xFF]));
    inputs.add("jpeg: SOI and a COM of length 2, then EOI", Buffer.from([0xFF, 0xD8, 0xFF, 0xFE, 0x00, 0x02, 0xFF, 0xD9]));
    inputs.add("jpeg: SOI and a COM cut after its length", Buffer.from([0xFF, 0xD8, 0xFF, 0xFE, 0x00, 0x03]));
    inputs.add("jpeg: SOI and a COM that ends the file", Buffer.from([0xFF, 0xD8, 0xFF, 0xFE, 0x00, 0x03, 0x41]));
}

// ── MPF ─────────────────────────────────────────────────────────────────────

/**
 * An APP2 MPF segment (CIPA DC-007): the MP header, the index IFD (its tags
 * [tag, type, count, value], 4 characters or a number) and the MP entries.
 * Everything can be wrong: the byte order mark, the magic, where the IFD and
 * the entries are (and where the header says the IFD is), how many entries
 * the IFD says it has, where the segment ends.
 */
function mpfIndex(T, { le = false, entries, tags = null, ifdAt = 8, ifdPointer = null, tableAt = null, mark = null, magic = 42, declared = null, cut = null }) {
    const table = Buffer.alloc(16 * entries.length);
    const w32 = (b, o, v) => (le ? b.writeUInt32LE(v, o) : b.writeUInt32BE(v, o));
    const w16 = (b, o, v) => (le ? b.writeUInt16LE(v, o) : b.writeUInt16BE(v, o));
    entries.forEach(({ attribute = 0, size, offset }, i) => { w32(table, 16 * i, attribute); w32(table, 16 * i + 4, size); w32(table, 16 * i + 8, offset); });
    const list = tags ?? [[0xB000, 7, 4, "0100"], [0xB001, 4, 1, entries.length], [0xB002, 7, 16 * entries.length, null]];
    const end = ifdAt + 2 + 12 * list.length + 4;
    const at = tableAt ?? end;
    const head = Buffer.alloc(Math.max(at + table.length, end));
    head.write(mark ?? (le ? "II" : "MM"), 0, "latin1");
    w16(head, 2, magic);
    w32(head, 4, ifdPointer ?? ifdAt);
    w16(head, ifdAt, declared ?? list.length);
    list.forEach(([tag, type, count, value], k) => {
        const p = ifdAt + 2 + 12 * k;
        w16(head, p, tag);
        w16(head, p + 2, type);
        w32(head, p + 4, count);
        if (typeof value === "string") head.write(value, p + 8, "latin1"); else w32(head, p + 8, value ?? at);
    });
    table.copy(head, at);
    return T.segment(0xE2, "MPF\0", cut === null ? head : head.subarray(0, cut));
}

function mpfCases({ inputs, T, SENTINEL }) {
    const gain = T.gainMapJpeg().gainMap;                       // JPEG, GPS EXIF, XMP and ISO 21496-1
    const withThumbnail = T.jpegWith([T.exifSegment(T.tiffBlock({ thumbnail: gpsThumbnail(T) }).block)], { base: T.THUMB_JPEG });
    const depth = T.jpegWith([T.exifSegment(T.tiffBlock({ exif: false, orientation: 1, gps: T.gpsEntries(SENTINEL.location) }).block)], { base: T.THUMB_JPEG });
    const cleanGain = T.jpegWith([T.isoGainMapSegment()], { base: T.THUMB_JPEG });
    const video = Buffer.concat([Buffer.from("\0\0\0\x18ftypmp42", "latin1"), Buffer.from(`moov ${SENTINEL.trailer}`, "latin1")]);
    const tail = Buffer.from(`SEFH ${SENTINEL.trailer} SEFT`, "latin1");
    const gpsExif = () => T.exifSegment(T.tiffBlock().block);
    const noGpsExif = () => T.exifSegment(T.tiffBlock({ gps: null }).block);

    /**
     * A photo with an MPF index and `images` after it, `gaps[i]` before the
     * i-th. `parts(index)` are the primary's segments (default: GPS EXIF, the
     * index, XMP after it, ISO 21496-1); `rewrite` changes the MP entries as
     * computed, `index` the index itself.
     */
    const photo = ({ le = false, images, gaps = [], parts = null, rewrite = (e) => e, trailer = null, firstSize = null, index = {} }) => {
        const primaryOf = (entries) => {
            const segment = mpfIndex(T, { le, entries, ...index });
            return T.jpegWith(parts ? parts(segment) : [gpsExif(), segment, T.xmpSegment(), T.isoGainMapSegment()]);
        };
        // The index is as long as its entries are many: size it with as many as it will have.
        const placeholders = rewrite(Array.from({ length: images.length + 1 }, () => ({ size: 0, offset: 0 })));
        const draft = primaryOf(placeholders);
        const mpfSegment = T.jpegSegments(draft).segments.find((s) => s.marker === 0xE2 && s.payload.toString("latin1", 0, 4) === "MPF\0");
        const base = mpfSegment ? mpfSegment.start + 8 : 0;
        const placed = [];
        let cursor = draft.length;
        images.forEach((image, i) => {
            const gap = gaps[i] ?? Buffer.alloc(0);
            placed.push({ size: image.length, offset: cursor + gap.length - base });
            cursor += gap.length + image.length;
        });
        const entries = rewrite([{ size: firstSize ?? draft.length, offset: 0, attribute: 0x20030000 }, ...placed]);
        return Buffer.concat([primaryOf(entries), ...images.flatMap((image, i) => [gaps[i] ?? Buffer.alloc(0), image]), ...(trailer ? [trailer] : [])]);
    };
    const add = (name, bytes) => inputs.add(`mpf: ${name}`, bytes);
    const cleanPrimary = (segment) => [noGpsExif(), segment, T.isoGainMapSegment()];

    // The index in each byte order, with what lies between and after the images.
    for (const le of [true, false]) {
        const order = le ? "little" : "big";
        const name = (what) => `${order}-endian index: ${what}`;
        add(name("a gain map, a video before it, a trailer"), photo({ le, images: [gain], gaps: [video], trailer: tail }));
        add(name("a gain map and a depth map, a video between them"), photo({ le, images: [gain, depth], gaps: [Buffer.alloc(0), video], trailer: tail }));
        add(name("two images listed in reverse order"), photo({ le, images: [gain, depth], rewrite: (e) => [e[0], e[2], e[1]] }));
        add(name("three images listed in the order 3, 1, 2"), photo({ le, images: [gain, depth, cleanGain], rewrite: (e) => [e[0], e[3], e[1], e[2]] }));
        add(name("an entry of size 0 between two images"), photo({ le, images: [gain, depth], rewrite: (e) => [e[0], e[1], { size: 0, offset: 99 }, e[2]] }));
        add(name("only the photo's own entry, a trailer after it"), photo({ le, images: [], trailer: tail }));
        add(name("nothing to change: a clean photo, clean images, one after the other"), photo({ le, images: [cleanGain, T.THUMB_JPEG], parts: cleanPrimary }));
        add(name("a clean photo, a clean image, a trailer"), photo({ le, images: [cleanGain], trailer: tail, parts: cleanPrimary }));
        add(name("the photo's own size is not its length, nothing else to change"), photo({ le, images: [cleanGain], firstSize: 1234, parts: cleanPrimary }));
        add(name("the photo's own size is 0xFFFFFFFF"), photo({ le, images: [gain], firstSize: 0xFFFFFFFF }));
        add(name("the photo's own size is 2^31"), photo({ le, images: [gain], firstSize: 0x80000000 }));
        add(name("an image with an index of its own (dropped from it)"),
            photo({ le, images: [T.jpegWith([T.exifSegment(T.tiffBlock({ exif: false }).block), mpfIndex(T, { le, entries: [{ size: 0, offset: 0 }] })], { base: T.THUMB_JPEG })] }));
        add(name("a second index in the photo (dropped)"), photo({ le, images: [gain], parts: (index) => [gpsExif(), index, mpfIndex(T, { le, entries: [{ size: 0, offset: 0 }] }), T.xmpSegment()] }));
        add(name("an image that is a JPEG with a thumbnail of its own"), photo({ le, images: [withThumbnail] }));
        // Indexes that do not describe the file.
        add(name("the second image starts inside the first (not at an FF D8)"), photo({ le, images: [gain, depth], rewrite: (e) => [e[0], e[1], { ...e[2], offset: e[1].offset + 10 }] }));
        {
            // Both entries start at an FF D8: the second is the first image's own EXIF thumbnail.
            const inner = withThumbnail.indexOf(Buffer.from([0xFF, 0xD8]), 2);
            add(name("an image that lies inside another (its thumbnail)"), photo({ le, images: [withThumbnail], rewrite: (e) => [e[0], e[1], { size: 60, offset: e[1].offset + inner }] }));
            add(name("an image that lies inside another, listed first"), photo({ le, images: [withThumbnail], rewrite: (e) => [e[0], { size: 60, offset: e[1].offset + inner }, e[1]] }));
        }
        add(name("an image at offset 0"), photo({ le, images: [gain], rewrite: (e) => [e[0], { ...e[1], offset: 0 }] }));
        add(name("an image that does not start with FF"), photo({ le, images: [Buffer.concat([Buffer.from([0x00]), gain.subarray(1)])] }));
        add(name("an image that does not start with FF D8"), photo({ le, images: [Buffer.concat([gain.subarray(0, 1), Buffer.from([0xD9]), gain.subarray(2)])] }));
        add(name("an image of one byte at the end of the file"), photo({ le, images: [Buffer.from([0xFF])] }));
        add(name("an image that ends past the file"), photo({ le, images: [gain], rewrite: (e) => [e[0], { ...e[1], size: e[1].size + 1 }] }));
        add(name("an image of size 0xFFFFFFFF"), photo({ le, images: [gain], rewrite: (e) => [e[0], { ...e[1], size: 0xFFFFFFFF }] }));
        add(name("an image at offset 0xFFFFFFFF"), photo({ le, images: [gain], rewrite: (e) => [e[0], { ...e[1], offset: 0xFFFFFFFF }] }));
        add(name("an image at offset 2^31"), photo({ le, images: [gain], rewrite: (e) => [e[0], { ...e[1], offset: 0x80000000 }] }));
        add(name("an image that starts inside the photo"), photo({ le, images: [gain], rewrite: (e) => [e[0], { ...e[1], offset: 10 }] }));
        add(name("the photo's own entry at an offset"), photo({ le, images: [gain], rewrite: (e) => [{ ...e[0], offset: 1 }, e[1]] }));
        add(name("the photo's own entry at 0xFFFFFFFF"), photo({ le, images: [gain], rewrite: (e) => [{ ...e[0], offset: 0xFFFFFFFF }, e[1]] }));
        add(name("a trailer that starts like a JPEG, after an index with no images"), photo({ le, images: [], trailer: Buffer.concat([Buffer.from([0xFF, 0xD8, 0xFF]), tail]) }));
    }

    // An index that cannot be read.
    const broken = (name, options) => add(`index that cannot be read: ${name}`, photo({ images: [gain], index: options }));
    const tag = (id, type, count, value) => [id, type, count, value];
    const version = tag(0xB000, 7, 4, "0100");
    broken("a byte order mark that is neither", { mark: "XX" });
    broken("not 42", { magic: 43 });
    broken("the header puts the IFD past the segment", { ifdPointer: 0x7000 });
    broken("the header puts the IFD at 0 (its count is the byte order mark)", { ifdPointer: 0 });
    broken("the header puts the IFD at 0xFFFFFFFF", { ifdPointer: 0xFFFFFFFF });
    broken("an IFD that says it has more entries than the segment holds", { declared: 5000 });
    broken("an IFD that ends exactly where the segment does (and no entries are there)", { cut: 8 + 2 + 36 });
    broken("no number of images", { tags: [version, tag(0xB002, 7, 32, null)] });
    broken("no MP entries", { tags: [version, tag(0xB001, 4, 1, 2)] });
    broken("a number of images that is SHORT", { tags: [version, tag(0xB001, 3, 1, 2), tag(0xB002, 7, 32, null)] });
    broken("a number of images of 2 values", { tags: [version, tag(0xB001, 4, 2, 2), tag(0xB002, 7, 32, null)] });
    broken("zero images", { tags: [version, tag(0xB001, 4, 1, 0), tag(0xB002, 7, 0, null)] });
    broken("MP entries of a type that is not UNDEFINED", { tags: [version, tag(0xB001, 4, 1, 2), tag(0xB002, 4, 32, null)] });
    broken("MP entries whose length is not 16 an image", { tags: [version, tag(0xB001, 4, 1, 2), tag(0xB002, 7, 31, null)] });
    broken("MP entries that run past the segment", { tags: [version, tag(0xB001, 4, 1, 2), tag(0xB002, 7, 32, 90)] });
    broken("MP entries at 0xFFFFFFFF", { tags: [version, tag(0xB001, 4, 1, 2), tag(0xB002, 7, 32, 0xFFFFFFFF)] });
    broken("a number of images of 0x10000001 and entries of 16 bytes", { tags: [version, tag(0xB001, 4, 1, 0x10000001), tag(0xB002, 7, 16, null)] });
    add("index that is read: the count and the entries each twice, the last of each counts",
        photo({ images: [gain], index: { tags: [tag(0xB001, 4, 1, 5), tag(0xB002, 7, 80, null), version, tag(0xB001, 4, 1, 2), tag(0xB002, 7, 32, null)] } }));
    broken("the segment cut inside the header", { cut: 5 });
    broken("the segment cut inside the IFD", { cut: 20 });
    broken("the segment cut inside the entries", { cut: 60 });
    broken("a segment that is only the header", { cut: 8 });
    broken("a segment of 7 bytes", { cut: 7 });
    for (const [why, parts] of [
        ["an APP2 that is only MPF", () => [gpsExif(), T.segment(0xE2, "MPF\0"), T.xmpSegment()]],
        ["an APP2 that is MPF and 3 bytes", () => [gpsExif(), T.segment(0xE2, "MPF\0", "II*"), T.xmpSegment()]],
        ["an APP2 that is a name like MPF", () => [gpsExif(), T.segment(0xE2, "MPG\0", "II*\0"), T.xmpSegment()]],
        ["an APP2 that is MPF after an ICC profile", (segment) => [gpsExif(), T.iccSegment(), segment, T.xmpSegment()]],
    ]) add(`segments: ${why}`, photo({ images: [gain], parts }));
}

// ── PNG ─────────────────────────────────────────────────────────────────────

function pngCases({ inputs, T, patched, SENTINEL }) {
    const add = (name, bytes) => inputs.add(`png: ${name}`, bytes);
    const p = (name, options) => add(name, T.png(options));
    const chunk = T.pngChunk;
    const exifChunk = (le = true, header = "") => chunk("eXIf", Buffer.concat([Buffer.from(header, "latin1"), T.tiffBlock({ le }).block]));

    // Ancillary chunks: those kept, those that are not.
    const kept = ["cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP", "mDCV", "mDCv", "cLLI", "cLLi", "bKGD", "hIST", "tRNS", "pHYs", "sPLT", "tIME", "acTL", "fcTL", "fdAT", "oFFs", "pCAL", "sCAL", "sTER", "gIFg", "iDOT"];
    // Ancillary (a lowercase first letter), registered or not, and spelled almost like one that is kept.
    const dropped = ["exIf", "zxIf", "caBX", "prVW", "vpAg", "dSIG", "gdAT", "orNT", "xmPP", "prVT", "cpIp", "aaaa", "zzzz", "iEND", "pHYS", "ster", "tIMe"];
    p("every ancillary chunk a decoder uses", { before: kept.map((type) => chunk(type, Buffer.from("data of that chunk", "latin1"))) });
    p("ancillary chunks that are not kept", { before: dropped.map((type) => chunk(type, `${SENTINEL.vendor} ${type}`)) });
    p("critical chunks stay, whatever their name: PLTE, XXXX, Zzzz", { before: [chunk("PLTE", Buffer.alloc(9, 3)), chunk("XXXX", SENTINEL.vendor), chunk("Zzzz", "reserved bit set")] });
    p("empty chunks", { before: [chunk("tIME"), chunk("prVW"), chunk("eXIf"), chunk("tEXt"), chunk("iTXt", "")] });
    p("chunks after the IDAT", { after: [chunk("tIME", Buffer.from([7, 234, 10, 5, 12, 0, 0])), chunk("prVW", SENTINEL.vendor), T.textChunk("tEXt", "Comment", "after the image")] });

    // Text chunks: the keyword says whether it goes.
    const keywords = {
        kept: ["Comment", "Title", "Author", "Description", "Copyright", "Creation Time", "Software", "Disclaimer", "Warning", "Source", "Comment ", " Comment", "Raw profile type", "raw profile typed", "XML:com.adobe.xmq",
            "Xml com adobe xma", "a".repeat(79), "caf\xE9", "\xFF\xFE", "G.P.S", "lat", "long", "lo cat"],
        dropped: ["XML:com.adobe.xmp", "xml:com.adobe.xmp", "XML:COM.ADOBE.XMP", "Raw profile type exif", "raw profile type iptc", "Raw profile type xmp", "Raw profile type 8bim", "Raw profile type APP1",
            "EXIF", "Exif", "exif data", "My XMP", "xmp", "IPTC", "Iptc4xmpCore", "GPS", "GPSLocation", "gps", "Geo", "geotag", "Geolocation", "Location", "location", "My Locations", "Latitude", "latitude", "Longitude", "LONGITUDE",
            "Photo exif", "XML:com.adobe.xmp extra", `${"a".repeat(40)}gps`, "ExIf", "GpS", "gEO", "LoCaT"],
    };
    for (const [verdict, list] of Object.entries(keywords)) {
        for (const [i, keyword] of list.entries()) {
            const type = ["tEXt", "zTXt", "iTXt"][i % 3];
            add(`text chunk ${type} with the keyword ${JSON.stringify(keyword)} (${verdict})`, T.png({ before: [T.textChunk(type, keyword, `text ${SENTINEL.location}`)] }));
        }
    }
    add("text chunks with no keyword to go by: empty, no NUL, one of 80 bytes before the NUL", T.png({ before: [
        chunk("tEXt", "\0no keyword"), chunk("tEXt", "no NUL at all"), chunk("zTXt", `${"k".repeat(80)}\0\0x`), chunk("iTXt", `${"k".repeat(79)}\0\0\0\0\0x`), chunk("tEXt", "k".repeat(81)), chunk("tEXt", ""),
    ] }));
    add("a text chunk whose NUL is the 80th byte", T.png({ before: [chunk("tEXt", Buffer.concat([Buffer.from("k".repeat(79)), Buffer.from([0]), Buffer.from("text")]))] }));
    add("a text chunk whose NUL is the 81st byte and whose keyword names GPS", T.png({ before: [chunk("tEXt", Buffer.concat([Buffer.from(`gps${"k".repeat(77)}`), Buffer.from([0, 0, 0]), Buffer.from("text")]))] }));
    add("a text chunk of 3 bytes", T.png({ before: [chunk("tEXt", "gps")] }));

    // eXIf in every shape.
    p("eXIf, little-endian", { before: [exifChunk(true)] });
    p("eXIf, big-endian", { before: [exifChunk(false)] });
    p("eXIf behind Exif\\0\\0", { before: [exifChunk(true, "Exif\0\0")] });
    p("eXIf behind Exif\\0\\0, big-endian", { before: [exifChunk(false, "Exif\0\0")] });
    p("eXIf of exactly Exif\\0", { before: [chunk("eXIf", "Exif\0")] });
    p("eXIf of Exif\\0 and one byte", { before: [chunk("eXIf", "Exif\0\0")] });
    p("eXIf of Exif\\0 and a block that is no TIFF", { before: [chunk("eXIf", "Exif\0\0not a TIFF block")] });
    p("eXIf of nothing", { before: [chunk("eXIf")] });
    p("eXIf that is no TIFF", { before: [chunk("eXIf", `no TIFF here ${SENTINEL.gps}`)] });
    p("eXIf with nothing to remove", { before: [chunk("eXIf", T.tiffBlock({ gps: null }).block)] });
    p("two eXIf chunks, one with GPS", { before: [exifChunk(true), exifChunk(false, "Exif\0\0")] });
    p("eXIf after the image data", { after: [exifChunk(true)] });
    p("eXIf with bytes after the TIFF block that nothing references", { before: [chunk("eXIf", Buffer.concat([T.tiffBlock().block, Buffer.from(`junk ${SENTINEL.location}`)]))] });

    // Chunks that cannot be delimited, a file cut short.
    const whole = T.png({ before: [exifChunk(true), T.textChunk("tEXt", "Comment", "kept")], after: [T.textChunk("iTXt", "XML:com.adobe.xmp", T.XMP_PACKET)] });
    const idat = whole.indexOf("IDAT");
    const iend = whole.indexOf("IEND");
    const first = 8 + 25;                                              // the first chunk after IHDR
    add("the signature and nothing else", T.PNG_SIGNATURE);
    add("the signature and 11 bytes", Buffer.concat([T.PNG_SIGNATURE, Buffer.alloc(11, 1)]));
    add("the signature and 12 bytes that are no chunk", Buffer.concat([T.PNG_SIGNATURE, Buffer.alloc(12, 0x31)]));
    add("the signature and a chunk type of digits", Buffer.concat([T.PNG_SIGNATURE, chunk("1234", "x")]));
    add("a chunk type with a NUL in it", Buffer.concat([whole.subarray(0, first), chunk("ab\0d", "x"), whole.subarray(first)]));
    add("a chunk type with a character between Z and a", Buffer.concat([whole.subarray(0, first), chunk("ab[d", "x"), whole.subarray(first)]));
    add("a chunk type with a letter outside ASCII", Buffer.concat([whole.subarray(0, first), chunk("ab\xE9d", "x"), whole.subarray(first)]));
    add("an IHDR of 12 bytes", patched(whole, [8, Buffer.from([0, 0, 0, 12])]));
    add("an IHDR of 14 bytes", patched(whole, [8, Buffer.from([0, 0, 0, 14])]));
    add("a chunk whose length is 2^31", patched(whole, [first, Buffer.from([0x80, 0, 0, 0])]));
    add("a chunk whose length is 0xFFFFFFFF", patched(whole, [first, Buffer.from([0xFF, 0xFF, 0xFF, 0xFF])]));
    add("a chunk whose length is 0xFFFFFFF4", patched(whole, [first, Buffer.from([0xFF, 0xFF, 0xFF, 0xF4])]));
    add("the file cut inside a chunk header", whole.subarray(0, first + 6));
    add("the file cut inside the eXIf data", whole.subarray(0, first + 20));
    add("the file cut right after the IDAT header", whole.subarray(0, idat + 4));
    add("the file cut inside the IDAT", whole.subarray(0, idat + 20));
    add("the file cut at the end of the IDAT data", whole.subarray(0, idat + 4 + 221));
    add("the file cut inside the IDAT CRC", whole.subarray(0, idat + 4 + 221 + 2));
    add("an IDAT whose length is wrong in a file that ends in IEND", patched(whole, [idat - 4, Buffer.from([0, 0, 0x7F, 0xFF])]));
    add("an IDAT whose length is wrong in a file cut before IEND", patched(whole, [idat - 4, Buffer.from([0, 0, 0x7F, 0xFF])]).subarray(0, iend - 4 - 10));
    add("an fdAT cut short", Buffer.concat([whole.subarray(0, idat - 4), chunk("fdAT", Buffer.alloc(60, 5)).subarray(0, 30)]));
    add("an fdAT whose length is wrong in a file that ends in IEND", Buffer.concat([whole.subarray(0, idat - 4), patched(chunk("fdAT", Buffer.alloc(8, 5)), [0, Buffer.from([0, 0, 0x7F, 0xFF])]), whole.subarray(iend - 4)]));
    add("a PLTE cut short", Buffer.concat([whole.subarray(0, first), chunk("PLTE", Buffer.alloc(30, 2)).subarray(0, 20)]));
    add("the image with no IEND", whole.subarray(0, iend - 4));
    add("the image and bytes after IEND", Buffer.concat([whole, Buffer.from(`after the end ${SENTINEL.trailer}`)]));
    add("the image and chunks after its IEND", Buffer.concat([whole, chunk("tEXt", "gps\0x"), chunk("IEND")]));
    add("an IEND with data", Buffer.concat([whole.subarray(0, iend - 4), chunk("IEND", "data")]));
    add("two IDAT chunks", Buffer.concat([whole.subarray(0, idat - 4), chunk("IDAT", Buffer.alloc(5, 1)), whole.subarray(idat - 4)]));
    add("an APNG: acTL, fcTL, IDAT, fcTL, fdAT", T.png({ before: [chunk("acTL", Buffer.alloc(8, 1)), chunk("fcTL", Buffer.alloc(26, 2))], after: [chunk("fcTL", Buffer.alloc(26, 3)), chunk("fdAT", Buffer.alloc(30, 4)), chunk("prVW", SENTINEL.vendor)] }));
    add("a chunk whose CRC is wrong (kept as it is)", patched(whole, [whole.indexOf("tEXt") + 4 + 12, Buffer.from([0x99])]));
    add("an eXIf whose CRC is wrong (recomputed)", patched(whole, [whole.indexOf("eXIf") + 4 + 30, Buffer.from([0x99])]));
}

// ── WebP ────────────────────────────────────────────────────────────────────

/** A RIFF WebP of `chunks`, its size right. */
function riffOf(chunks) {
    const body = Buffer.concat([Buffer.from("WEBP"), ...chunks]);
    const head = Buffer.alloc(8);
    head.write("RIFF", 0, "latin1");
    head.writeUInt32LE(body.length, 4);
    return Buffer.concat([head, body]);
}

/** `bytes` with the RIFF size `size`. */
function withRiffSize(bytes, size) {
    const out = Buffer.from(bytes);
    out.writeUInt32LE(size, 4);
    return out;
}

function webpCases({ inputs, T, patched, SENTINEL }) {
    const add = (name, bytes) => inputs.add(`webp: ${name}`, bytes);
    const w = (name, chunks, options) => add(name, T.webp(chunks, options));
    const chunk = T.riffChunk;
    const exif = (le = true, header = "") => chunk("EXIF", Buffer.concat([Buffer.from(header, "latin1"), T.tiffBlock({ le }).block]));
    const xmp = () => chunk("XMP ", T.XMP_PACKET);
    const bad = chunk("EXIF", `not a TIFF block ${SENTINEL.gps}`);
    const vp8x = (flags) => chunk("VP8X", Buffer.from([flags, 0, 0, 0, 15, 0, 0, 7, 0, 0]));

    // Flags and chunks.
    w("EXIF only", [exif()]);
    w("XMP only", [xmp()]);
    w("EXIF and XMP, flags as they should be", [exif(), xmp()]);
    w("EXIF and XMP, no flags", [exif(), xmp()], { flags: 0 });
    w("EXIF and XMP, every flag", [exif(), xmp()], { flags: 0xFF });
    w("EXIF and XMP, the EXIF flag only", [exif(), xmp()], { flags: 0x08 });
    w("EXIF and XMP, the XMP flag only", [exif(), xmp()], { flags: 0x04 });
    w("a good EXIF and one that cannot be parsed", [exif(), bad]);
    w("one that cannot be parsed and then a good EXIF", [bad, exif()]);
    w("two EXIF chunks that cannot be parsed", [bad, bad]);
    w("two XMP chunks", [xmp(), xmp()]);
    w("EXIF with Exif\\0\\0 in front", [exif(true, "Exif\0\0")]);
    w("EXIF of exactly Exif\\0", [chunk("EXIF", "Exif\0")]);
    w("EXIF of Exif\\0 and one byte", [chunk("EXIF", "Exif\0\0")]);
    w("EXIF that is empty", [chunk("EXIF", "")]);
    w("EXIF with nothing to remove", [chunk("EXIF", T.tiffBlock({ gps: null }).block)]);
    w("EXIF of an odd size (a pad byte)", [chunk("EXIF", Buffer.concat([T.tiffBlock().block, Buffer.from([0x5A])]))]);
    w("every chunk a decoder uses: ICC profile, alpha, animation, lossless", [chunk("ICCP", Buffer.alloc(33, 1)), chunk("ALPH", Buffer.alloc(11, 2)), chunk("ANIM", Buffer.alloc(6, 3)), chunk("ANMF", Buffer.alloc(24, 4)), chunk("VP8L", Buffer.alloc(9, 5))]);
    w("chunks nothing reads", [chunk("ZZZZ", SENTINEL.vendor), chunk("C2PA", SENTINEL.c2pa), chunk("GPS ", SENTINEL.gps), chunk("exif", "lowercase"), chunk("ZZ", "short")]);
    w("a second VP8X", [chunk("VP8X", Buffer.alloc(10, 1)), exif()]);
    w("a second VP8X of 4 bytes", [chunk("VP8X", Buffer.alloc(4, 1)), exif()]);
    w("odd sizes and their pad bytes", [chunk("ZZZZ", "a"), chunk("ZZZY", "abc"), chunk("EXIF", Buffer.concat([T.tiffBlock({ gps: null }).block, Buffer.from([1])]))]);
    add("a simple WebP (no VP8X) with an EXIF chunk", riffOf([T.VP8_CHUNK, exif()]));
    add("a simple WebP (no VP8X) with XMP, EXIF and a bad EXIF", riffOf([T.VP8_CHUNK, xmp(), exif(), bad]));
    add("a lossless WebP with EXIF", riffOf([vp8x(0x08), chunk("VP8L", Buffer.alloc(20, 6)), exif()]));
    add("a WebP with no chunks", riffOf([]));
    add("a WebP with 4 bytes of a chunk header", riffOf([Buffer.from("VP8 ", "latin1")]));
    add("a WebP with 7 bytes after the VP8X", riffOf([vp8x(0x0C), Buffer.alloc(7, 1)]));
    add("a WebP with an empty EXIF chunk after the VP8X", riffOf([vp8x(0x0C), chunk("EXIF", "")]));
    add("VP8X flags for EXIF and XMP, neither present", riffOf([vp8x(0x0C), T.VP8_CHUNK]));
    add("VP8X flags for XMP, an EXIF that is dropped", riffOf([vp8x(0x0C), T.VP8_CHUNK, bad]));

    // The RIFF size, and chunks that cannot be delimited.
    const whole = T.webp([exif(), xmp(), chunk("ZZZZ", "x")]);
    const riff = whole.readUInt32LE(4);
    const vp8At = 12 + 18;                                              // the VP8 chunk, after the VP8X
    const exifAt = whole.indexOf("EXIF", vp8At);
    const xmpAt = whole.indexOf("XMP ", vp8At);
    add("bytes after the RIFF", Buffer.concat([whole, Buffer.from(`after ${SENTINEL.trailer}`)]));
    for (const [why, size] of [["shorter than the file", riff - 40], ["longer than the file", riff + 200], ["0xFFFFFFFF", 0xFFFFFFFF], ["2^31", 0x80000000], ["0", 0], ["4", 4], ["5", 5],
        ["12, the VP8X header with no data", 12], ["30, the VP8X and 4 bytes after it", 30], ["odd", riff - 1], ["ending inside the EXIF chunk", exifAt - 8 + 30]]) {
        add(`a RIFF size ${why}`, withRiffSize(whole, size));
    }
    for (const [why, n] of [["inside the RIFF header", 10], ["right after WEBP", 12], ["inside a chunk header", vp8At + 5], ["right after a chunk header", vp8At + 8], ["inside the VP8 data", vp8At + 40],
        ["inside the EXIF data", exifAt + 20], ["inside the XMP data", xmpAt + 30], ["inside the last chunk's pad", whole.length - 1]]) {
        add(`the file cut ${why}`, whole.subarray(0, n));
    }
    add("a VP8X of 9 bytes", patched(whole, [16, Buffer.from([9, 0, 0, 0])]));
    add("a VP8X of 11 bytes", patched(whole, [16, Buffer.from([11, 0, 0, 0])]));
    add("a VP8X of 0xFFFFFFFF bytes", patched(whole, [16, Buffer.from([0xFF, 0xFF, 0xFF, 0xFF])]));
    add("a VP8 chunk of 2^31 bytes", patched(whole, [vp8At + 4, Buffer.from([0, 0, 0, 0x80])]));
    add("a VP8 chunk longer than the file but inside the RIFF size", patched(whole, [vp8At + 4, Buffer.from([0xF0, 0, 0, 0])]));
    add("a VP8 chunk longer than the RIFF size", patched(whole, [vp8At + 4, Buffer.from([0xFF, 0xFF, 0, 0])]));
    add("an EXIF chunk longer than the file", patched(whole, [exifAt + 4, Buffer.from([0xF0, 0xFF, 0, 0])]));
    add("an EXIF chunk of 0xFFFFFFFF bytes", patched(whole, [exifAt + 4, Buffer.from([0xFF, 0xFF, 0xFF, 0xFF])]));
    add("an XMP chunk longer than the file", patched(whole, [xmpAt + 4, Buffer.from([0xF0, 0xFF, 0, 0])]));
    // Image data and other chunks cut short.
    for (const fourcc of ["VP8L", "ALPH", "ANMF", "ICCP", "ANIM", "ZZZZ"]) {
        const cut = riffOf([vp8x(0), chunk(fourcc, Buffer.alloc(40, 6)), ...(fourcc === "ALPH" ? [T.VP8_CHUNK] : [])]);
        add(`a ${fourcc} chunk cut short`, cut.subarray(0, 12 + 18 + 8 + 25));
    }
    const flagged = (fourcc) => riffOf([vp8x(0x0C), T.VP8_CHUNK, chunk(fourcc, Buffer.alloc(40, 6))]);
    add("an XMP chunk cut short, flags set", flagged("XMP ").subarray(0, 12 + 18 + 86 + 8 + 25));
    add("an EXIF chunk cut short, flags set", flagged("EXIF").subarray(0, 12 + 18 + 86 + 8 + 25));
    add("a chunk longer than the RIFF size at the end of the file", patched(riffOf([vp8x(0), T.VP8_CHUNK, xmp()]), [12 + 18 + 86 + 4, Buffer.from([0xFF, 0xFF, 0xFF, 0x0F])]));
}
