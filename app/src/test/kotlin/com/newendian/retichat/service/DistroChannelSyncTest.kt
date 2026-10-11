package com.newendian.retichat.service

import com.newendian.retichat.service.DistroChannelSync.Disposition
import com.newendian.retichat.service.DistroChannelSync.Record
import com.newendian.retichat.ui.channels.ChannelNameForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * RFed-rust SPEC §17.12 "Membership sync": the message C as received (field
 * encodings, any integer encoding of `at_ms`), receive rules 1-5, rule 5's
 * order and the repeat key, against the spec's own vectors and the web's.
 */
class DistroChannelSyncTest {
    private val device = "a".repeat(32)
    private val sibling = "b".repeat(32)
    private val distro = "d".repeat(32)

    // ---- msgpack, written by hand: the oracle is the spec's table, not this codebase's reader ----

    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) when (p) {
            is ByteArray -> out.write(p)
            is Int -> out.write(p)
            is List<*> -> p.forEach { out.write(it as Int) }
            else -> error("bad part $p")
        }
        return out.toByteArray()
    }

    private fun str(s: String): ByteArray {
        val raw = s.toByteArray()
        return when {
            raw.size < 32 -> bytes(0xa0 or raw.size, raw)
            else -> bytes(0xd9, raw.size, raw)
        }
    }

    private fun bin(raw: ByteArray): ByteArray = bytes(0xc4, raw.size, raw)

    private fun be(v: Long, n: Int): ByteArray = ByteBuffer.allocate(8).putLong(v).array().copyOfRange(8 - n, 8)

    /** An integer in a given msgpack encoding. */
    private fun uint64(v: Long) = bytes(0xcf, be(v, 8))
    private fun int64(v: Long) = bytes(0xd3, be(v, 8))
    private fun uint32(v: Long) = bytes(0xce, be(v, 4))
    private fun uint16(v: Long) = bytes(0xcd, be(v, 2))
    private fun uint8(v: Long) = bytes(0xcc, be(v, 1))
    private fun fixint(v: Int) = bytes(v)
    private fun float64(v: Double) = bytes(0xcb, be(java.lang.Double.doubleToLongBits(v), 8))
    private fun float32(v: Float) = bytes(0xca, be(java.lang.Float.floatToIntBits(v).toLong(), 4))

    private fun array(vararg items: ByteArray): ByteArray = bytes(0x90 or items.size, *items)

    /** A fixmap of (key bytes, value bytes) pairs. */
    private fun map(vararg entries: Pair<ByteArray, ByteArray>): ByteArray =
        bytes(0x80 or entries.size, *entries.flatMap { listOf(it.first, it.second) }.toTypedArray())

    private fun key(k: Int): ByteArray = if (k < 0x80) bytes(k) else bytes(0xcc, k)

    /** C's fields as §17.12's table gives them, `at` as given. */
    private fun fields(
        op: String = "join", name: String = "public.general", at: ByteArray = uint64(1_767_225_600_000),
        by: String = sibling, type: ByteArray = str(DistroChannelSync.TYPE), data: ByteArray? = null,
    ): ByteArray = map(
        key(0x0c) to bin(ByteArray(0)),
        key(0xfb) to type,
        key(0xfc) to (data ?: array(str(op), str(name), at)),
        key(0xfd) to str(by),
    )

    // ---- the web's and rmpv's bytes ----

    /** Retichat-js: int 64 for 2026-01-01 00:00 UTC, `d3 00 00 01 9b 76 da a8 00` (§17.12). */
    private val webAt = bytes(0xd3, listOf(0x00, 0x00, 0x01, 0x9b, 0x76, 0xda, 0xa8, 0x00))

    /**
     * The fields of C as the phones write them (rust/retichat-jni `distro_outbox.rs`
     * pins this very literal for the writer): join, `public.general`, 2026-01-01
     * 00:00 UTC as uint 64, sent by device 0123...cdef.
     */
    private val phonesC = "840cc400ccfbb3726665642e64697374726f2e6368616e6e656cccfc93a46a6f696eae7075626c69632e67656e6572616ccf0000019b76daa800ccfdd9203031323334353637383961626364656630313233343536373839616263646566"

    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun theBytesThePhonesWriteAreReadBackAsTheActionTheyCarry() {
        val marker = DistroChannelSync.read(unhex(phonesC))!!
        assertNull(marker.problem)
        assertEquals(DistroChannelSync.Sync("join", "public.general", 1_767_225_600_000L), marker.sync)
        assertEquals("0123456789abcdef0123456789abcdef", marker.byHex)
        // And they are the very bytes the test's own builder makes of the same fields.
        assertEquals(phonesC, fields(at = uint64(1_767_225_600_000), by = "0123456789abcdef0123456789abcdef")
            .joinToString("") { "%02x".format(it) })
    }

    @Test
    fun theWebsInt64AndRmpvsUint64HoldingOneValueAreTheSameAction() {
        assertEquals(1_767_225_600_000L, 0x19b76daa800L)
        val fromWeb = DistroChannelSync.read(fields(at = webAt))
        val fromPhone = DistroChannelSync.read(fields(at = uint64(1_767_225_600_000)))
        assertNotNull(fromWeb)
        assertEquals(fromWeb, fromPhone)
        assertEquals(DistroChannelSync.Sync("join", "public.general", 1_767_225_600_000L), fromWeb!!.sync)
        assertNull(fromWeb.problem)
        assertEquals(sibling, fromWeb.byHex)
    }

    @Test
    fun everyIntegerEncodingIsTakenAndTheValueIsJudgedNeverTheTypeByte() {
        for (at in listOf(
            fixint(5), uint8(200), uint16(60_000), uint32(4_000_000_000), uint64(5), int64(5),
            bytes(0xd0, 5), bytes(0xd1, be(5, 2)), bytes(0xd2, be(5, 4)),
        )) {
            val marker = DistroChannelSync.read(fields(at = at))
            assertNull(at.joinToString(",") { "%02x".format(it) }, marker!!.problem)
        }
        assertEquals(5L, DistroChannelSync.read(fields(at = bytes(0xd2, be(5, 4))))!!.sync!!.atMs)
        assertEquals(0L, DistroChannelSync.read(fields(at = fixint(0)))!!.sync!!.atMs)
        assertEquals(200L, DistroChannelSync.read(fields(at = uint8(200)))!!.sync!!.atMs)
        assertEquals(4_000_000_000L, DistroChannelSync.read(fields(at = uint32(4_000_000_000)))!!.sync!!.atMs)
    }

    @Test
    fun aFloatANegativeAStrAndNilAreNoAtMs() {
        for (at in listOf(
            float64(1_767_225_600_000.0), float64(5.0), float32(5f),
            int64(-1), bytes(0xd0, 0xff), bytes(0xff),
            str("5"), bytes(0xc0), bytes(0xc3), bin(byteArrayOf(5)),
        )) {
            val marker = DistroChannelSync.read(fields(at = at))
            assertNull(at.joinToString(",") { "%02x".format(it) }, marker!!.sync)
            assertTrue(marker.problem!!.contains("at_ms"))
        }
    }

    @Test
    fun atMsIsBoundedAt2To53Minus1InAnyEncoding() {
        val max = (1L shl 53) - 1
        assertEquals(max, DistroChannelSync.MAX_AT_MS)
        assertEquals(max, DistroChannelSync.read(fields(at = uint64(max)))!!.sync!!.atMs)
        assertEquals(max, DistroChannelSync.read(fields(at = int64(max)))!!.sync!!.atMs)
        // 2^53 and 2^53 + 1, as uint 64 and int 64, and 2^64 - 1: the clients would order them differently.
        for (at in listOf(uint64(max + 1), uint64(max + 2), int64(max + 1), uint64(-1L), uint64(Long.MIN_VALUE))) {
            val marker = DistroChannelSync.read(fields(at = at))
            assertNull(at.joinToString(",") { "%02x".format(it) }, marker!!.sync)
            assertTrue(marker.problem!!.contains("2^53"))
        }
    }

    @Test
    fun theFieldsOfCAsWrittenAreReadBackWhole() {
        // The writer (nativeMessageSetDistroChannelSync) writes 0x0C as an empty bin, 0xFB as a
        // str, 0xFC as a native array [op, name, at_ms] and 0xFD as a str of the device's address.
        val marker = DistroChannelSync.read(fields(op = "leave", name = "0123456789abcdef.team", by = device.uppercase()))!!
        assertEquals(DistroChannelSync.Sync("leave", "0123456789abcdef.team", 1_767_225_600_000L), marker.sync)
        // 0xFD is lowercased.
        assertEquals(device, marker.byHex)
    }

    @Test
    fun aNameAnOpAndATypeMayBeStrOrBin() {
        // "receivers accept bin too": the type, the op and the name as bin.
        val marker = DistroChannelSync.read(
            fields(
                data = array(bin("join".toByteArray()), bin("public.general".toByteArray()), uint64(5)),
                type = bin(DistroChannelSync.TYPE.toByteArray()),
            )
        )!!
        assertEquals(DistroChannelSync.Sync("join", "public.general", 5), marker.sync)
    }

    @Test
    fun elementsAfterTheThirdAreIgnoredAndFewerThanThreeAreUnusable() {
        val extra = DistroChannelSync.read(fields(data = array(str("join"), str("public.x"), uint64(7), str("later"), uint64(1))))!!
        assertEquals(DistroChannelSync.Sync("join", "public.x", 7), extra.sync)
        val two = DistroChannelSync.read(fields(data = array(str("join"), str("public.x"))))!!
        assertNull(two.sync)
        assertTrue(two.problem!!.contains("2 element"))
    }

    @Test
    fun bytesHoldingPackedMsgpackAreNotAnArray() {
        // CHECK_THESE_THINGS_FIRST §11: never a bin holding packed msgpack.
        val wrapped = DistroChannelSync.read(fields(data = bin(array(str("join"), str("public.x"), uint64(7)))))!!
        assertNull(wrapped.sync)
        assertTrue(wrapped.problem!!.contains("bin, not an array"))
    }

    @Test
    fun anOpThatIsNotJoinOrLeaveAndANameThatIsEmptyOrNotUtf8AreUnusable() {
        val badOp = DistroChannelSync.read(fields(op = "Join"))!!
        assertTrue(badOp.problem!!.contains("\"Join\""))
        val noOp = DistroChannelSync.read(fields(data = array(uint64(1), str("public.x"), uint64(7))))!!
        assertTrue(noOp.problem!!.contains("not text"))
        val empty = DistroChannelSync.read(fields(name = ""))!!
        assertTrue(empty.problem!!.contains("name"))
        val notUtf8 = DistroChannelSync.read(fields(data = array(str("join"), bin(byteArrayOf(0xff.toByte(), 0xfe.toByte())), uint64(7))))!!
        assertNull(notUtf8.sync)
        assertTrue(notUtf8.problem!!.contains("name"))
        val nameNotText = DistroChannelSync.read(fields(data = array(str("join"), uint64(5), uint64(7))))!!
        assertNull(nameNotText.sync)
    }

    @Test
    fun aMessageThatIsNotAMembershipMessageIsNull() {
        assertNull(DistroChannelSync.read(null))
        assertNull(DistroChannelSync.read(ByteArray(0)))
        assertNull(DistroChannelSync.read(map(key(0xfb) to str("rfed.distro.sent"))))
        assertNull(DistroChannelSync.read(map(key(0xfc) to array(str("join"), str("public.x"), fixint(1)))))
        assertNull(DistroChannelSync.read(map()))
        // Not a map at all.
        assertNull(DistroChannelSync.read(array(str("join"))))
        assertNull(DistroChannelSync.read(byteArrayOf(0xc0.toByte())))
        // A type that is not text.
        assertNull(DistroChannelSync.read(map(key(0xfb) to uint64(1))))
    }

    @Test
    fun aKeyMayBeInAnyIntegerWidth() {
        val wide = map(
            bytes(0xcc, 0xfb) to str(DistroChannelSync.TYPE),
            bytes(0xcd, 0x00, 0xfc) to array(str("join"), str("public.x"), fixint(3)),
            bytes(0xd3, be(0xfd, 8)) to str(sibling),
        )
        assertEquals(DistroChannelSync.Sync("join", "public.x", 3), DistroChannelSync.read(wide)!!.sync)
    }

    @Test
    fun aMembershipMessageThePythonReferenceRefusesIsRefusedNeverReadFromOneEntry() {
        // RFed SPEC §17.14 "Repeated keys", which settles §17.12's readers too (James,
        // 2026-10-11), and "Unhashable keys": the distro unwrap refuses such a message whole first;
        // given one anyway (a native library built before LXMF-rust b873c00), this reader reads none
        // of its entries and gives the reference's reason. Until 2026-10-11 it read the first entry
        // of a key; until this change it refused only a repeated integer key of the fields map.
        fun refused(c: ByteArray, says: String) {
            val marker = DistroChannelSync.read(c)!!
            assertNull(marker.sync)
            assertEquals("", marker.byHex)
            assertTrue(marker.problem!!, marker.problem!!.startsWith("refused whole: $says, as Python LXMF refuses it (umsgpack "))
        }
        val join = array(str("join"), str("public.first"), fixint(1))
        val c = key(0xfb) to str(DistroChannelSync.TYPE)
        refused(map(c, key(0xfc) to join, key(0xfc) to array(str("leave"), str("public.second"), fixint(2))), "a map at depth 1 repeats a key (252)")
        refused(map(key(0xfb) to str("app.example"), c, key(0xfc) to join), "a map at depth 1 repeats a key (251)")
        refused(map(c, key(0xfc) to join, key(0xfd) to str(device), key(0xfd) to str(sibling)), "a map at depth 1 repeats a key (253)")
        refused(map(key(0x0c) to bin(ByteArray(0)), key(0x0c) to bin(ByteArray(0)), c, key(0xfc) to join), "a map at depth 1 repeats a key (12)")
        // Python's key equality, not the integer's encoding: 0xFC as uint 16, and as the float 252.0.
        refused(map(c, key(0xfc) to join, uint16(0xfc) to join), "a map at depth 1 repeats a key (252)")
        refused(map(c, key(0xfc) to join, float64(252.0) to join), "a map at depth 1 repeats a key (252.0)")
        // In a map no accessor reads, and with a str key.
        refused(map(c, key(0xfc) to join, key(0x09) to array(map(str("k") to fixint(1), str("k") to fixint(2)))), "a map at depth 3 repeats a key (\"k\")")
        // A key Python cannot hash: a map, or an array holding one at any depth.
        refused(map(c, key(0xfc) to join, bytes(0x80) to fixint(0)), "a map at depth 1 has a key Python cannot hash (map 80)")
        refused(map(c, key(0xfc) to join, array(fixint(1), array(bytes(0x80))) to fixint(0)), "a map at depth 1 has a key Python cannot hash (array 92019180)")
        // A repeat in a message no entry of whose 0xFB names C: no membership message.
        assertNull(DistroChannelSync.read(map(key(0xfb) to str("app.example"), key(0xfb) to str("app.other"), key(0xfc) to join)))
    }

    @Test
    fun aTypedMessageWithNoOrBrokenDataIsUnusableNeverAnOrdinaryMessage() {
        val none = DistroChannelSync.read(map(key(0xfb) to str(DistroChannelSync.TYPE), key(0xfd) to str(sibling)))!!
        assertNull(none.sync)
        assertEquals("it carries no 0xFC", none.problem)
        assertEquals(sibling, none.byHex)
        val cut = fields(op = "join", name = "public.general")
        val truncated = DistroChannelSync.read(cut.copyOf(cut.size - 3))
        // A field map too broken to be read is dropped as no membership message at all.
        assertTrue(truncated == null || truncated.sync == null)
    }

    @Test
    fun hostileBytesNeverThrow() {
        // A claimed array of four billion (or 2^31 - 1) elements, nesting deeper than any
        // real field, every truncation: each is read as far as its bytes go and no further.
        val bomb = bytes(0x82, key(0xfb), str(DistroChannelSync.TYPE), key(0xfc), 0xdd, listOf(0xff, 0xff, 0xff, 0xff))
        assertNull(DistroChannelSync.read(bomb))
        val large = bytes(0x82, key(0xfb), str(DistroChannelSync.TYPE), key(0xfc), 0xdd, listOf(0x7f, 0xff, 0xff, 0xff))
        assertNull(DistroChannelSync.read(large))
        val deep = ByteArray(5000) { 0x91.toByte() }
        assertNull(DistroChannelSync.read(bytes(0x81, key(0xfb), deep)))
        for (cut in 0..fields().size) DistroChannelSync.read(fields().copyOf(cut))
        assertNull(DistroChannelSync.read(byteArrayOf(0xc1.toByte())))
    }

    // ---- rule 5 ----

    @Test
    fun aLaterTimeWinsAndAtAnEqualTimeALeaveReplacesAJoinButNeverTheOtherWayAround() {
        val joinAt10 = Record("join", 10)
        val leaveAt10 = Record("leave", 10)
        assertTrue(DistroChannelSync.supersedes("join", 11, joinAt10))
        assertTrue(DistroChannelSync.supersedes("leave", 11, joinAt10))
        assertTrue(!DistroChannelSync.supersedes("join", 9, leaveAt10))
        assertTrue(!DistroChannelSync.supersedes("leave", 9, leaveAt10))
        assertTrue(DistroChannelSync.supersedes("leave", 10, joinAt10))
        assertTrue(!DistroChannelSync.supersedes("join", 10, leaveAt10))
        // The same action again at the same time changes nothing.
        assertTrue(!DistroChannelSync.supersedes("join", 10, joinAt10))
        assertTrue(!DistroChannelSync.supersedes("leave", 10, leaveAt10))
        assertTrue(DistroChannelSync.supersedes("join", 0, null))
    }

    @Test
    fun theUsersOwnActionIsStampedAfterAnyRecordHeld() {
        assertEquals(1_000L, DistroChannelSync.stampLocal(null, 1_000))
        assertEquals(1_000L, DistroChannelSync.stampLocal(Record("join", 400), 1_000))
        // The record is later than the clock: one more than it.
        assertEquals(2_001L, DistroChannelSync.stampLocal(Record("leave", 2_000), 1_000))
        // At the clock's own time: one more, so the action is strictly newer.
        assertEquals(1_001L, DistroChannelSync.stampLocal(Record("leave", 1_000), 1_000))
    }

    // ---- rules 1-5 and the devices' state ----

    private fun channelHashOf(name: String): String =
        MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).copyOf(16).joinToString("") { "%02x".format(it) }

    /** One of the user's devices: its channel list and rule 5's record, applying what it receives. */
    private inner class Phone(val address: String, var clock: Long) {
        val records = mutableMapOf<String, Record>()
        val joined = mutableSetOf<String>()
        val log = mutableListOf<String>()

        fun disposition(marker: DistroChannelSync.Marker, fromDistro: Boolean = true, signed: Boolean = true) =
            DistroChannelSync.disposition(
                marker, fromDistro, signed, address,
                joinNameAccepted = ChannelNameForm::joinNameAccepted,
                channelHashOf = ::channelHashOf,
                recordOf = { records[it] },
            )

        /** The user's own action here: recorded and stamped, then C is made. */
        fun act(op: String, name: String): ByteArray {
            val hash = channelHashOf(name)
            val at = DistroChannelSync.stampLocal(records[hash], clock)
            records[hash] = Record(op, at)
            if (op == "join") joined += hash else joined -= hash
            return fields(op = op, name = name, at = uint64(at), by = address)
        }

        /** C arrives (rules 1-5, then 6 for an Apply). */
        fun receive(c: ByteArray, fromDistro: Boolean = true, signed: Boolean = true): Disposition {
            val marker = DistroChannelSync.read(c)!!
            val d = disposition(marker, fromDistro, signed)
            when (d) {
                is Disposition.Apply -> {
                    if (d.op == "join") joined += d.channelHash else joined -= d.channelHash
                    records[d.channelHash] = Record(d.op, d.atMs)
                }
                is Disposition.Stale, Disposition.Echo, is Disposition.Drop -> Unit
            }
            return d
        }
    }

    @Test
    fun theRulesRunInOrderAndTheFirstThatDropsEndsTheCheck() {
        val phone = Phone(device, 1_000)
        val ok = DistroChannelSync.read(fields())!!
        // Rule 1.
        assertEquals(1, (phone.disposition(ok, fromDistro = false, signed = false) as Disposition.Drop).rule)
        // Rule 2: a claimed source D with no valid signature by D's key.
        assertEquals(2, (phone.disposition(ok, signed = false) as Disposition.Drop).rule)
        // Rule 3 comes after both: an own echo that fails the signature is a drop, not an echo.
        val own = DistroChannelSync.read(fields(by = device))!!
        assertEquals(2, (phone.disposition(own, signed = false) as Disposition.Drop).rule)
        assertEquals(Disposition.Echo, phone.disposition(own))
        // An own echo is an echo even when what it says is unusable (rule 3 before rule 4).
        val ownBroken = DistroChannelSync.read(fields(by = device, op = "bogus"))!!
        assertEquals(Disposition.Echo, phone.disposition(ownBroken))
        // Rule 4.
        val broken = DistroChannelSync.read(fields(op = "bogus"))!!
        val drop4 = phone.disposition(broken) as Disposition.Drop
        assertEquals(4, drop4.rule)
        assertTrue(drop4.reason.contains("bogus"))
        // Rule 4: a join whose name this client's own join refuses.
        val refused = DistroChannelSync.read(fields(op = "join", name = "public.Test"))!!
        assertEquals(4, (phone.disposition(refused) as Disposition.Drop).rule)
    }

    @Test
    fun aLeaveOfAChannelJoinedBeforeTheNameRulesIsAppliedAndAJoinOfThatNameIsDropped() {
        val phone = Phone(device, 1_000)
        val hash = channelHashOf("public.Test")
        phone.joined += hash
        val leave = DistroChannelSync.read(fields(op = "leave", name = "public.Test", at = uint64(5)))!!
        val d = phone.disposition(leave)
        assertEquals(Disposition.Apply("leave", "public.Test", 5, hash), d)
        val join = DistroChannelSync.read(fields(op = "join", name = "public.Test", at = uint64(5)))!!
        assertEquals(4, (phone.disposition(join) as Disposition.Drop).rule)
    }

    @Test
    fun aMessageOlderThanTheRecordIsStaleAndRecordedAsSeen() {
        val phone = Phone(device, 1_000)
        val hash = channelHashOf("public.general")
        phone.records[hash] = Record("leave", 500)
        val old = DistroChannelSync.read(fields(op = "join", name = "public.general", at = uint64(400)))!!
        val d = phone.disposition(old)
        assertEquals(Disposition.Stale("join", "public.general", 400, hash, Record("leave", 500)), d)
    }

    @Test
    fun aJoinAndALeaveWithOneTimeLeaveEveryDeviceLeftInEitherOrder() {
        val join = fields(op = "join", name = "public.general", at = uint64(1_000), by = sibling)
        val leave = fields(op = "leave", name = "public.general", at = uint64(1_000), by = "c".repeat(32))
        for (order in listOf(listOf(join, leave), listOf(leave, join))) {
            val phone = Phone(device, 5_000)
            order.forEach { phone.receive(it) }
            assertTrue("every device ends left", phone.joined.isEmpty())
            assertEquals(Record("leave", 1_000), phone.records[channelHashOf("public.general")])
        }
    }

    @Test
    fun concurrentActionsEndInOneStateTheOneWithTheLargerAtMs() {
        val a = fields(op = "join", name = "public.general", at = uint64(2_000), by = sibling)
        val b = fields(op = "leave", name = "public.general", at = uint64(1_500), by = "c".repeat(32))
        for (order in listOf(listOf(a, b), listOf(b, a))) {
            val phone = Phone(device, 9_000)
            order.forEach { phone.receive(it) }
            assertEquals(setOf(channelHashOf("public.general")), phone.joined)
        }
    }

    @Test
    fun aDeviceWithItsClockTenMinutesBehindStampsItsLaterJoinAfterASiblingsLeave() {
        val tenMinutes = 600_000L
        val a = Phone(sibling, 1_000_000)
        val b = Phone(device, 1_000_000 - tenMinutes)
        val name = "public.general"
        // Both are in the channel; A leaves at its clock. B takes A's record, then joins by its own (slow) clock.
        a.joined += channelHashOf(name); b.joined += channelHashOf(name)
        val leave = a.act("leave", name)
        b.receive(leave)
        assertTrue(b.joined.isEmpty())
        val join = b.act("join", name)
        a.receive(join)
        assertEquals("every device ends joined", setOf(channelHashOf(name)), a.joined)
        assertEquals(setOf(channelHashOf(name)), b.joined)
        // Stamped by the clock alone, B's join would be older than A's leave and A would end left.
        assertTrue(DistroChannelSync.read(join)!!.sync!!.atMs > DistroChannelSync.read(leave)!!.sync!!.atMs)
        val clockAlone = fields(op = "join", name = name, at = uint64(b.clock), by = device)
        val a2 = Phone(sibling, 1_000_000)
        a2.joined += channelHashOf(name)
        a2.receive(leave.let { fields(op = "leave", name = name, at = uint64(1_000_000), by = "c".repeat(32)) })
        a2.receive(clockAlone)
        assertTrue("a clock-alone stamp leaves the devices disagreeing", a2.joined.isEmpty())
    }

    @Test
    fun anOwnEchoIsDroppedAndAppliedChangesAreNeverReSent() {
        val phone = Phone(device, 1_000)
        val c = phone.act("join", "public.general")
        // RFed fans C out to the sender too: dropped by 0xFD, the record untouched.
        assertEquals(Disposition.Echo, phone.receive(c))
        assertEquals(1, phone.records.size)
    }

    // ---- the repeat key ----

    @Test
    fun theSameCTwiceIsOneMessageAndTwoSiblingsCsInOneMillisecondAreTwo() {
        val atMs = uint64(1_000)
        val fromSibling = DistroChannelSync.read(fields(op = "join", name = "public.x", at = atMs, by = sibling))!!
        val fromOther = DistroChannelSync.read(fields(op = "leave", name = "public.x", at = atMs, by = "c".repeat(32)))!!
        val ts = 1_767_225_600.123
        // Same source, same LXMF timestamp, empty content: the generic key would be one.
        assertEquals(DistroCodec.seenKey(distro, ts), DistroCodec.seenKey(distro, ts))
        assertNotEquals(
            DistroChannelSync.repeatKey(distro, ts, fromSibling),
            DistroChannelSync.repeatKey(distro, ts, fromOther),
        )
        // Two uploads of one C, or its live arrival and its pull, are the same key.
        val again = DistroChannelSync.read(fields(op = "join", name = "public.x", at = int64(1_000), by = sibling))!!
        assertEquals(
            DistroChannelSync.repeatKey(distro, ts, fromSibling),
            DistroChannelSync.repeatKey(distro, ts, again),
        )
        // A different action by the same device in the same instant is another message too.
        val laterAction = DistroChannelSync.read(fields(op = "leave", name = "public.x", at = uint64(1_001), by = sibling))!!
        assertNotEquals(
            DistroChannelSync.repeatKey(distro, ts, fromSibling),
            DistroChannelSync.repeatKey(distro, ts, laterAction),
        )
    }

    @Test
    fun entryIdIsTheChannelsSoALaterActionReplacesIt() {
        assertEquals("channel:abc", DistroChannelSync.entryId("abc"))
    }
}
