package com.newendian.retichat.data.repository

import com.newendian.retichat.bridge.GroupEntry
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.data.model.toHex
import com.newendian.retichat.names.Signature
import com.newendian.retichat.service.GroupEnvelopes
import com.newendian.retichat.service.GroupFields
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SHARED VECTORS FOR MEMBER STATUSES AND KEYS: LXMF-rust
 * `tests/group_status_vectors.json` (RFed-spec Group.md, "Member statuses and
 * keys (2026-10-06)"; DISPLAY_NAMES.md §10 key 10), the file Retichat-js,
 * Retichat-ios and Retichat-android check themselves against. The copy in
 * `rust/retichat-jni/tests/data` is the one the Rust tests run through the real
 * glue (`group_status.rs`) and check against LXMF-rust's; here every vector goes
 * through the Kotlin layer ([GroupStatusEngine], [DeliveryPolicy],
 * [GroupEnvelopes], [LxmfFields]) with the JNI behind [FakeGroupNative], which
 * checks real Ed25519 signatures over the real packed messages. It mirrors the
 * web's `group_status_vectors.test.mjs`.
 */
class GroupStatusVectorsTest {
    private val v = GroupStatusVectors.load()
    private val holders = mapOf("none" to emptyList(), "all" to listOf("alice", "bob", "carol", "dave", "erin", "frank"))

    private fun verdictName(signature: Int) = when (signature) {
        Signature.VALIDATED -> "verified"
        Signature.INVALID -> "forged"
        else -> "unverifiable"
    }

    private fun outcome(signature: Int, switched: Boolean) = when (GroupStatuses.verdict(signature, switched)) {
        GroupStatuses.Verdict.COUNT -> "counts"
        GroupStatuses.Verdict.HOLD -> "held"
        GroupStatuses.Verdict.IGNORE -> "ignored"
    }

    // ── the file, as a phone reads it ─────────────────────────────────

    @Test
    fun theIdentitiesAreWhatTheFileSaysPublicKeyHashAndKeyEntryDeriveFromThePrivateKey() {
        assertEquals(listOf("creator", "alice", "bob", "carol", "dave", "erin", "frank", "mallory"), v.identities.keys.toList())
        for (identity in v.identities.values) {
            // The loader already checked the lxmf.delivery hash and the entry against the file.
            val b64 = identity.keyEntry.substringAfter(":")
            assertTrue(identity.name, Regex("^[A-Za-z0-9+/]{86}==$").matches(b64))
            assertEquals(64, identity.publicKey.size)
        }
        assertEquals(
            v.members.joinToString(",") { v.hashOf(it) },
            (v.root["group"] as Map<*, *>)["group_members"],
        )
    }

    @Test
    fun theTestPackingReproducesTheFilesBytesSoTheMessagesTheScenariosMakeAreTheFilesKind() {
        assertTrue(v.messages.size >= 9)
        for (m in v.messages.values) {
            val retichat = m.form == "retichat"
            val key = m.carriesKeyOf?.let { holder -> "${v.hashOf(m.keyEntryHashOf!!)}:${java.util.Base64.getEncoder().encodeToString(v.identities.getValue(holder).publicKey)}" }
            val fields = TestLxmf.groupFields(retichat, v.groupId, m.action, v.hashOf(m.source), key, v.displayNames.getValue(m.source))
            assertEquals("${m.name}: the fields", m.fields.toHex(), fields.toHex())
            val packed = TestLxmf.pack(v.identities.getValue(m.signedBy), v.identities.getValue(m.source).hash, v.identities.getValue(m.destination).hash, fields, m.timestamp)
            assertEquals("${m.name}: the packed message, signature included", m.packed.toHex(), packed.toHex())
        }
        for (s in v.statuses.values) {
            val listed = s.statuses.map { (_, name) -> v.messages.getValue(name).packed }
            val fields = TestLxmf.groupFields(s.form == "retichat", v.groupId, "status", v.hashOf(v.creator), null, v.displayNames.getValue(v.creator), listed)
            assertEquals("${s.name}: the fields", s.fields.toHex(), fields.toHex())
            val packed = TestLxmf.pack(v.identities.getValue(v.creator), v.identities.getValue(v.creator).hash, v.identities.getValue(s.destination).hash, fields, s.timestamp)
            assertEquals("${s.name}: the packed message, signature included", s.packed.toHex(), packed.toHex())
        }
    }

    @Test
    fun everyMessagesVerdictForAReceiverHoldingNoMembersKeyAndOneHoldingEveryMembersIsTheFilesAndWhatItIsWorthFollows() {
        for ((context, names) in holders) {
            for (m in v.messages.values) {
                val native = FakeGroupNative().also { n -> names.forEach { n.know(v.identities.getValue(it)) } }
                val receipt = native.receipt(m.packed) ?: error("${m.name} does not unpack")
                val u = receipt.unpacked
                assertEquals("${m.name}: holding $context", m.verdict.getValue(context), verdictName(u.signature))
                assertEquals("${m.name}: before the switch, holding $context", m.outcomeBeforeSwitch.getValue(context), outcome(u.signature, false))
                assertEquals("${m.name}: from the switch, holding $context", m.outcomeFromSwitch.getValue(context), outcome(u.signature, true))
                // The key it carries, when it binds, is the sender's own: reported whether a key is held or not.
                val binds = m.carriesKeyOf != null && m.keyEntryHashOf == m.source && m.carriesKeyOf == m.source
                assertEquals("${m.name}: a key that binds is the sender's own", binds, u.senderKey != null)
                if (binds) assertArrayEquals(v.identities.getValue(m.source).publicKey, u.senderKey)
                assertArrayEquals("${m.name}: the copy a creator keeps is the message as received", m.packed, receipt.packed)
                assertEquals(m.hashHex, u.hash.toHex())
                assertArrayEquals(m.fields, u.fieldsRaw)
                val fields = LxmfFields.decode(u.fieldsRaw)
                assertEquals(v.groupId, fields.group(GroupEntry.ID))
                assertEquals(m.action, fields.group(GroupEntry.ACTION))
                assertEquals(v.hashOf(m.source), fields.group(GroupEntry.SENDER))
                // Where the entries are, by form: the old top-level fields, or the Retichat field.
                if (m.form == "legacy") {
                    assertTrue(m.name, fields.has(0xA0) && fields.has(0xA3) && fields.has(0xA4))
                    assertEquals(m.name, m.carriesKeyOf != null, fields.has(0xA8))
                } else {
                    assertFalse(m.name, (0xA0..0xA8).any { fields.has(it) })
                }
            }
        }
    }

    @Test
    fun theStatusesAreSignedByTheCreatorAndKeyTenIsAnArrayOfBinHoldingExactlyTheListedMessagesByMemberHashAscending() {
        assertEquals(4, v.statuses.size)
        for (s in v.statuses.values) {
            val withCreator = FakeGroupNative().also { it.know(v.identities.getValue("creator")) }
            assertEquals("${s.name}: signed by the creator", Signature.VALIDATED, withCreator.receipt(s.packed)!!.unpacked.signature)
            assertEquals("${s.name}: and by nobody whose key is not held", Signature.SOURCE_UNKNOWN, FakeGroupNative().receipt(s.packed)!!.unpacked.signature)
            assertEquals(s.packed.copyOfRange(0, 16).toHex(), v.hashOf(s.destination))
            val fields = LxmfFields.decode(s.fields)
            assertEquals(v.groupId, fields.group(GroupEntry.ID))
            assertEquals("status", fields.group(GroupEntry.ACTION))
            assertEquals(v.hashOf(v.creator), fields.group(GroupEntry.SENDER))
            val elements = fields.groupStatuses()!!
            assertTrue("${s.name}: an array of bin", elements.all { it is ByteArray })
            assertEquals("${s.name}: the listed messages, byte for byte",
                s.statuses.map { v.messages.getValue(it.second).packed.toHex() }, elements.map { (it as ByteArray).toHex() })
            val members = s.statuses.map { it.first }
            assertEquals("${s.name}: one element for each member", members.size, members.toSet().size)
            assertEquals("${s.name}: by member hash ascending", members.map { v.hashOf(it) }.sorted(), members.map { v.hashOf(it) })
            assertFalse("${s.name}: neither the creator's nor the recipient's own", "creator" in members || s.destination in members)
            for ((member, message) in s.statuses) assertEquals(member, v.messages.getValue(message).source)
            // Key 10 is in the Retichat field whatever the form; the other entries are where the form puts them.
            assertFalse("${s.name}: key 10 has no old field", fields.has(10))
            assertEquals(s.form == "legacy", fields.has(0xA0))
        }
    }

    // ── the receiver of a status, from the file's bytes ───────────────

    @Test
    fun aReceiverOfStatusToFrankDoesElementByElementWhatTheFileSaysHoldingNoMembersKeyAndEveryMembersBeforeTheSwitchAndFromItInEitherForm() = runBlocking {
        assertEquals(4, v.receiverOutcomes.size)
        for (expected in v.receiverOutcomes) for (form in listOf("legacy", "retichat")) {
            val label = "holding ${if (expected.holdsKeysOf.size == 1) "no member's key" else "every member's key"}, ${expected.switch} the switch, $form form"
            val rig = GroupRig(v, expected.receiver, switched = expected.switch == "from", holds = expected.holdsKeysOf)
            // Frank has accepted the group: the creator accepted, frank accepted, the rest are invited.
            rig.hold(accepted = true)
            rig.receive(v.statuses.getValue("${expected.status}_$form").packed)

            assertEquals(label, expected.memberStatusAfter, rig.statuses())
            assertEquals("$label: held",
                expected.heldAfter.sortedBy { it.first },
                rig.store.heldRows.map { v.nameOf(it.srcHex) to it.action }.sortedBy { it.first })
            val outcomes = expected.elements.map { it.outcome }
            val tally = rig.lastTally!!
            assertEquals(label, listOf(outcomes.size, outcomes.count { it == "counted" }, outcomes.count { it == "held" }, outcomes.count { it == "skipped" }),
                listOf(tally.elements, tally.counted, tally.held, tally.skipped))
            // The notices: one for each member that counted, "left the group" for a leave, said once each.
            val said = rig.store.lines.values.map { (if (it.joined) "joined" else "left") to v.nameOf(it.member) }.sortedBy { it.second }
            val want = expected.elements.filter { it.outcome == "counted" }
                .map { (if (v.messages.getValue(it.message).action == "leave") "left" else "joined") to it.member }.sortedBy { it.second }
            assertEquals("$label: said once each", want, said)
            // The key an element carries is remembered as one arriving directly brings it, and an accept
            // element allows its member in the group the user has joined (as any accept does).
            val acceptsThatCounted = expected.elements
                .filter { it.outcome == "counted" && v.messages.getValue(it.message).action == "accept" }.map { it.member }.toSet()
            assertEquals("$label: allowed", acceptsThatCounted, rig.store.allowed.map { v.nameOf(it) }.toSet())
            for (e in expected.elements) {
                val m = v.messages.getValue(e.message)
                val binds = m.carriesKeyOf == m.source && m.keyEntryHashOf == m.source
                assertEquals("$label: ${e.message}: remembered", binds && e.outcome != "skipped", v.hashOf(e.member) in rig.store.keys)
            }
        }
    }

    @Test
    fun aStatusWithNoElementsFromTheFileIsTheCreatorsValidAnswerAndChangesNothing() = runBlocking {
        for (form in listOf("legacy", "retichat")) {
            val rig = GroupRig(v, "alice", holds = listOf("creator"))
            rig.hold(accepted = true)
            val before = rig.statuses()
            rig.receive(v.statuses.getValue("status_empty_to_alice_$form").packed)
            assertEquals(form, StatusTally(0, 0, 0, 0), rig.lastTally)
            assertEquals(form, before, rig.statuses())
            assertTrue(rig.store.lines.isEmpty())
        }
    }

    // ── the creator, from the file's bytes ────────────────────────────

    @Test
    fun aCreatorThatReceivesEachMessageAnswersItWithAStatusExactlyWhenTheFileSaysOnlyAnAcceptThatCarriesItsSendersOwnKey() = runBlocking {
        for ((context, names) in holders) for (m in v.messages.values) {
            val rig = GroupRig(v, v.creator, holds = names)
            // The group the vectors name, created by this device: it is the creator and has accepted it.
            rig.hold(accepted = true)
            assertEquals(v.hashOf(v.creator), rig.store.chats.getValue(rig.chatId).creatorHex)
            rig.receive(m.packed)
            assertEquals("${m.name}, the creator holding $context", if (m.creatorAnswers) listOf(m.source) else emptyList<String>(), rig.answeredNames())
            // What it keeps follows what counted: a copy of exactly the bytes, or none.
            val counted = m.outcomeBeforeSwitch.getValue(context) == "counts"
            assertEquals("${m.name}, the creator holding $context: kept only when it counts",
                if (counted) listOf(Triple(m.source, m.action, m.packed.toHex())) else emptyList<Triple<String, String, String>>(),
                rig.store.copyRows.values.map { Triple(v.nameOf(it.memberHex), it.action, it.packed.toHex()) })
        }
    }

    // ── the sender, against the file ──────────────────────────────────

    /** Records the native calls a [GroupFields.Sink] would make. */
    private class Recorder : GroupFields.Sink {
        val calls = mutableListOf<String>()
        override fun addFieldString(key: Int, value: String) = calls.add("field ${key.toString(16)} $value")
        override fun addFieldBool(key: Int, value: Boolean) = calls.add("field ${key.toString(16)} $value")
        override fun setRetichatString(key: Int, value: String) = calls.add("retichat $key $value")
        override fun setRetichatBool(key: Int, value: Boolean) = calls.add("retichat $key $value")
        override fun setRetichatStatuses(elements: List<ByteArray>) = calls.add("retichat 10 ${elements.size}")
    }

    @Test
    fun theShippedSenderWritesAlicesAcceptWithHerOwnKeyEntryInTheOldFieldBeforeTheSwitchAndInKeyNineAfter() {
        val alice = v.identities.getValue("alice")
        for (switched in listOf(false, true)) {
            val sink = Recorder()
            assertTrue(GroupEnvelopes.write(sink, GroupEnvelopes.accept(v.groupId, alice.hashHex, alice.keyEntry), switched))
            val spec = v.messages.getValue(if (switched) "alice_accept_keyed_retichat" else "alice_accept_keyed_legacy")
            val vector = LxmfFields.decode(spec.fields)
            // The same entries as the vector, in the form it is in.
            assertEquals(v.groupId, vector.group(GroupEntry.ID))
            assertEquals("accept", vector.group(GroupEntry.ACTION))
            assertEquals(alice.hashHex, vector.group(GroupEntry.SENDER))
            assertEquals("the entry as the file spells it", alice.keyEntry, vector.group(GroupEntry.MEMBER_KEYS))
            val expected = if (switched) {
                listOf("retichat 1 ${v.groupId}", "retichat 4 accept", "retichat 5 ${alice.hashHex}", "retichat 9 ${alice.keyEntry}")
            } else {
                listOf("field a0 ${v.groupId}", "field a3 accept", "field a4 ${alice.hashHex}", "field a8 ${alice.keyEntry}")
            }
            assertEquals(expected, sink.calls)
            // The vector keeps the old fields only before the switch; after it key 9 is in the Retichat field alone.
            assertEquals(!switched, vector.has(0xA8))
            assertEquals(1, vector.group(GroupEntry.MEMBER_KEYS)!!.split(",").size)
        }
    }

    @Test
    fun aCreatorsStatusIsWrittenAsTheFileHasItTheEntriesInTheFormAndKeyTenInTheRetichatFieldEitherWay() {
        val creator = v.identities.getValue("creator")
        for (switched in listOf(false, true)) {
            val sink = Recorder()
            assertTrue(GroupEnvelopes.write(sink, GroupEnvelopes.status(v.groupId, creator.hashHex), switched))
            assertTrue(GroupFields.setStatuses(sink, listOf(byteArrayOf(1))))
            val entries = if (switched) {
                listOf("retichat 1 ${v.groupId}", "retichat 4 status", "retichat 5 ${creator.hashHex}")
            } else {
                listOf("field a0 ${v.groupId}", "field a3 status", "field a4 ${creator.hashHex}")
            }
            assertEquals(entries + "retichat 10 1", sink.calls)
        }
    }

    @Test
    fun theFilesGroupAndItsNamesAreWhatTheScenariosAssume() {
        assertEquals(listOf("creator", "alice", "bob", "carol", "dave", "erin", "frank"), v.members)
        assertEquals("creator", v.creator)
        assertNotNull(v.messages["alice_accept_keyed_legacy"])
        assertNull(v.messages["nobody"])
    }
}
