package com.newendian.retichat.data.repository

import com.newendian.retichat.GroupMemberStatuses
import com.newendian.retichat.MemberStatus
import com.newendian.retichat.bridge.GroupEntry
import com.newendian.retichat.bridge.GroupNative
import com.newendian.retichat.bridge.GroupUnpack
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.bridge.MiniJson
import com.newendian.retichat.bridge.UnpackedGroupMessage
import com.newendian.retichat.data.db.entity.ChatEntity
import com.newendian.retichat.data.db.entity.GroupMemberEntity
import com.newendian.retichat.data.model.toHex
import com.newendian.retichat.names.Signature
import com.newendian.retichat.service.GroupChatManager.Action
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/*
 * Test doubles for the group follow-up (RFed-spec Group.md, "Member statuses and
 * keys (2026-10-06)"): the JVM cannot load libretichat_jni.so, so the native
 * layer is a fake that checks REAL Ed25519 signatures over REAL packed LXMF
 * messages (BouncyCastle, which the app already has), a store in memory, and
 * the shared vectors (LXMF-rust tests/group_status_vectors.json, whose copy in
 * rust/retichat-jni/tests/data the Rust tests check against the LXMF-rust
 * branch) read the way the Rust tests read them. The fake does what the Rust
 * glue does (group_status.rs), and the Rust tests run the same vectors through
 * the real glue, so the two cannot drift apart unseen.
 */

/** msgpack, written by hand: the shortest forms, as the vectors' generator writes them. */
object Mp {
    fun cat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun head(small: Int, base: Int, wide16: Int, n: Int): ByteArray = when {
        n < small -> byteArrayOf((base or n).toByte())
        n <= 0xFFFF -> byteArrayOf(wide16.toByte(), (n shr 8).toByte(), n.toByte())
        else -> error("too long for a test")
    }

    fun str(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        val h = when {
            b.size < 32 -> byteArrayOf((0xA0 or b.size).toByte())
            b.size <= 0xFF -> byteArrayOf(0xD9.toByte(), b.size.toByte())
            else -> byteArrayOf(0xDA.toByte(), (b.size shr 8).toByte(), b.size.toByte())
        }
        return h + b
    }

    fun bin(b: ByteArray): ByteArray {
        val h = when {
            b.size <= 0xFF -> byteArrayOf(0xC4.toByte(), b.size.toByte())
            else -> byteArrayOf(0xC5.toByte(), (b.size shr 8).toByte(), b.size.toByte())
        }
        return h + b
    }

    fun f64(d: Double): ByteArray {
        val bits = java.lang.Double.doubleToRawLongBits(d)
        return byteArrayOf(0xCB.toByte()) + ByteArray(8) { (bits shr (56 - 8 * it)).toByte() }
    }

    /** A non-negative integer: one byte to 127, `cc xx` to 255 (the form rmpv writes keys over 0x7f in). */
    fun int(n: Int): ByteArray = if (n < 0x80) byteArrayOf(n.toByte()) else byteArrayOf(0xCC.toByte(), n.toByte())

    fun array(items: List<ByteArray>): ByteArray = head(16, 0x90, 0xDC, items.size) + cat(*items.toTypedArray())

    fun map(entries: List<Pair<ByteArray, ByteArray>>): ByteArray =
        head(16, 0x80, 0xDE, entries.size) + cat(*entries.flatMap { listOf(it.first, it.second) }.toTypedArray())

    /** The index after the msgpack value that starts at [at]. */
    fun end(b: ByteArray, at: Int): Int {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
        val t = u8(at)
        return when {
            t < 0x80 || t >= 0xE0 -> at + 1
            t in 0x80..0x8F -> (0 until 2 * (t and 0x0F)).fold(at + 1) { i, _ -> end(b, i) }
            t in 0x90..0x9F -> (0 until (t and 0x0F)).fold(at + 1) { i, _ -> end(b, i) }
            t in 0xA0..0xBF -> at + 1 + (t and 0x1F)
            t == 0xC0 || t == 0xC2 || t == 0xC3 -> at + 1
            t == 0xC4 || t == 0xD9 -> at + 2 + u8(at + 1)
            t == 0xC5 || t == 0xDA -> at + 3 + u16(at + 1)
            t == 0xC6 || t == 0xDB -> at + 5 + u32(at + 1)
            t == 0xCA || t == 0xCE || t == 0xD2 -> at + 5
            t == 0xCB || t == 0xCF || t == 0xD3 -> at + 9
            t == 0xCC || t == 0xD0 -> at + 2
            t == 0xCD || t == 0xD1 -> at + 3
            t == 0xDC -> (0 until u16(at + 1)).fold(at + 3) { i, _ -> end(b, i) }
            t == 0xDE -> (0 until 2 * u16(at + 1)).fold(at + 3) { i, _ -> end(b, i) }
            else -> error("msgpack type 0x${t.toString(16)} is not one a test message holds")
        }
    }
}

/** An identity of the vectors (or a made one): its 64-byte public key, its seed and its lxmf.delivery hash. */
class TestIdentity(val name: String, val seed: ByteArray, val publicKey: ByteArray) {
    val hash: ByteArray = TestLxmf.deliveryHash(publicKey)
    val hashHex: String = hash.toHex()

    /** `hash:base64-public-key`, the entry an invite chunk carries and an accept or leave carries for its own sender. */
    val keyEntry: String get() = GroupStatuses.keyEntry(hashHex, publicKey)
}

/** The packing, signing and checking of LXMF messages, as LXMessage.py and the Rust glue do them. */
object TestLxmf {
    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").also { d -> parts.forEach { d.update(it) } }.digest()

    /** The `lxmf.delivery` destination hash of a 64-byte public key. */
    fun deliveryHash(publicKey: ByteArray): ByteArray {
        val identityHash = sha256(publicKey).copyOf(16)
        val nameHash = sha256("lxmf.delivery".toByteArray()).copyOf(10)
        return sha256(nameHash, identityHash).copyOf(16)
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 32))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    /** A made identity: a seed from [n], a public key that is no real x25519 point and does not need to be. */
    fun identity(name: String, n: Int): TestIdentity {
        val seed = sha256("seed $name $n".toByteArray())
        val edPublic = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        return TestIdentity(name, seed, sha256("x25519 $name $n".toByteArray()) + edPublic)
    }

    /**
     * The fields of a group message in either form (RFed-spec Group.md; the
     * vectors' `legacy` and `retichat`): the entries in the old top-level fields
     * 0xA0-0xA8 and the name alone in 0xD1, or every entry and the name in 0xD1.
     * [keyEntry] is the GROUP_MEMBER_KEYS entry (null: none), [name] the Message
     * Display Name (a bin at key 0). Keys in the order the vectors write them.
     */
    fun groupFields(
        retichat: Boolean,
        groupId: String,
        action: String,
        sender: String,
        keyEntry: String?,
        name: String = "N",
        statuses: List<ByteArray>? = null,
    ): ByteArray {
        val entries = buildList {
            add(1 to groupId); add(4 to action); add(5 to sender)
            if (keyEntry != null) add(9 to keyEntry)
        }
        val retichatEntries = mutableListOf<Pair<ByteArray, ByteArray>>(Mp.int(0) to Mp.bin(name.toByteArray()))
        val top = mutableListOf<Pair<ByteArray, ByteArray>>()
        if (retichat) {
            entries.forEach { (key, value) -> retichatEntries += Mp.int(key) to Mp.str(value) }
        } else {
            entries.forEach { (key, value) -> top += Mp.int(0xA0 + key - 1) to Mp.str(value) }
        }
        if (statuses != null) retichatEntries += Mp.int(10) to Mp.array(statuses.map { Mp.bin(it) })
        top += Mp.int(0xD1) to Mp.map(retichatEntries)
        return Mp.map(top)
    }

    /**
     * A packed LXMF message `destination | source | signature | payload`, signed by
     * [signer] (not necessarily the source's own: that is how a forgery is made).
     * The payload is `[timestamp, title, content, fields]`, and a fifth element, a
     * stamp, when [stamp] (which is not signed).
     */
    fun pack(
        signer: TestIdentity,
        sourceHash: ByteArray,
        destinationHash: ByteArray,
        fields: ByteArray,
        timestamp: Double,
        content: ByteArray = ByteArray(0),
        stamp: Boolean = false,
    ): ByteArray {
        val core = Mp.array(listOf(Mp.f64(timestamp), Mp.bin(ByteArray(0)), Mp.bin(content), fields))
        val hash = sha256(destinationHash, sourceHash, core)
        val signature = sign(signer.seed, destinationHash + sourceHash + core + hash)
        val payload = if (stamp) {
            byteArrayOf(0x95.toByte()) + core.copyOfRange(1, core.size) + Mp.bin(ByteArray(32) { 9 })
        } else core
        return destinationHash + sourceHash + signature + payload
    }
}

/** One message of the vectors file. */
class VectorMessage(
    val name: String,
    val action: String,
    val source: String,
    val destination: String,
    val signedBy: String,
    val carriesKeyOf: String?,
    val keyEntryHashOf: String?,
    val form: String,
    val timestamp: Double,
    val fields: ByteArray,
    val packed: ByteArray,
    val hashHex: String,
    val verdict: Map<String, String>,
    val outcomeBeforeSwitch: Map<String, String>,
    val outcomeFromSwitch: Map<String, String>,
    val creatorAnswers: Boolean,
)

class VectorStatus(
    val name: String,
    val destination: String,
    val form: String,
    val timestamp: Double,
    val fields: ByteArray,
    val packed: ByteArray,
    /** (member, message name), in the order the status lists them. */
    val statuses: List<Pair<String, String>>,
)

class ElementOutcome(val member: String, val message: String, val verdict: String, val outcome: String)

class ReceiverOutcome(
    val status: String,
    val receiver: String,
    val holdsKeysOf: List<String>,
    val switch: String,
    val elements: List<ElementOutcome>,
    val memberStatusAfter: Map<String, String>,
    val heldAfter: List<Pair<String, String>>,
)

/** LXMF-rust `tests/group_status_vectors.json`, the shared vectors, as the JVM tests read them. */
class GroupStatusVectors private constructor(val root: Map<String, Any?>) {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = root[key] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun strings(m: Any?) = (m as Map<String, Any?>).mapValues { it.value as String }

    /** The Message Display Name each identity's messages carry (a bin at key 0 of the Retichat field). */
    val displayNames: Map<String, String> = list("identities").associate { (it["name"] as String) to (it["display_name"] as String) }

    val identities: Map<String, TestIdentity> = list("identities").associate { i ->
        val name = i["name"] as String
        val identity = TestIdentity(name, hex(i["private_key_hex"] as String).copyOfRange(32, 64), hex(i["public_key_hex"] as String))
        check(identity.hashHex == i["lxmf_delivery_hash_hex"]) { "$name: lxmf.delivery hash" }
        check(identity.keyEntry == i["key_entry"]) { "$name: key entry" }
        name to identity
    }

    @Suppress("UNCHECKED_CAST")
    private val group = root["group"] as Map<String, Any?>
    val groupId: String = group["group_id"] as String
    val creator: String = group["creator"] as String

    @Suppress("UNCHECKED_CAST")
    val members: List<String> = group["members"] as List<String>

    val messages: Map<String, VectorMessage> = list("messages").associate { m ->
        val name = m["name"] as String
        name to VectorMessage(
            name = name, action = m["action"] as String, source = m["source"] as String,
            destination = m["destination"] as String, signedBy = m["signed_by"] as String,
            carriesKeyOf = m["carries_key_of"] as String?, keyEntryHashOf = m["key_entry_hash_of"] as String?,
            form = m["form"] as String, timestamp = (m["timestamp"] as Number).toDouble(),
            fields = hex(m["fields_msgpack_hex"] as String), packed = hex(m["packed_hex"] as String),
            hashHex = m["message_hash_hex"] as String, verdict = strings(m["verdict"]),
            outcomeBeforeSwitch = strings(m["outcome_before_switch"]), outcomeFromSwitch = strings(m["outcome_from_switch"]),
            creatorAnswers = m["creator_answers"] as Boolean,
        )
    }

    val statuses: Map<String, VectorStatus> = list("statuses").associate { s ->
        val name = s["name"] as String
        @Suppress("UNCHECKED_CAST")
        val listed = (s["statuses"] as List<Map<String, Any?>>).map { (it["member"] as String) to (it["message"] as String) }
        name to VectorStatus(
            name, s["destination"] as String, s["form"] as String, (s["timestamp"] as Number).toDouble(),
            hex(s["fields_msgpack_hex"] as String), hex(s["packed_hex"] as String), listed,
        )
    }

    val receiverOutcomes: List<ReceiverOutcome> = list("receiver_outcomes").map { r ->
        @Suppress("UNCHECKED_CAST")
        ReceiverOutcome(
            status = r["status"] as String, receiver = r["receiver"] as String,
            holdsKeysOf = r["holds_keys_of"] as List<String>, switch = r["switch"] as String,
            elements = (r["elements"] as List<Map<String, Any?>>).map {
                ElementOutcome(it["member"] as String, it["message"] as String, it["verdict"] as String, it["outcome"] as String)
            },
            memberStatusAfter = strings(r["member_status_after"]),
            heldAfter = (r["held_after"] as List<Map<String, Any?>>).map { (it["member"] as String) to (it["action"] as String) },
        )
    }

    fun hashOf(name: String): String = identities.getValue(name).hashHex
    fun nameOf(hash: String): String = identities.values.first { it.hashHex == hash }.name

    companion object {
        /**
         * The project root (the directory with settings.gradle.kts), whatever the
         * test's working directory. The file is the copy the JNI crate's Rust tests
         * run and check against LXMF-rust's (rust/retichat-jni/tests/data): a
         * missing file fails the test rather than skipping it.
         */
        val projectRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("no settings.gradle.kts above ${File("").absolutePath}")

        val file: File get() = File(projectRoot, "rust/retichat-jni/tests/data/group_status_vectors.json")

        @Suppress("UNCHECKED_CAST")
        fun load(): GroupStatusVectors {
            check(file.exists()) { "shared vectors not found at $file" }
            return GroupStatusVectors(MiniJson.parse(file.readText()) as Map<String, Any?>)
        }
    }
}

/**
 * The native layer on the JVM ([GroupNative]), doing what the Rust glue does
 * (`group_status.rs`): it holds keys ([know], [rememberKey]), unpacks a packed
 * LXMF message and checks its signature under the key held for its source, or,
 * when none is held, under the key an accept or leave carries for its own
 * sender once that binds. Signatures are real (Ed25519).
 */
class FakeGroupNative : GroupNative {
    /** The identity store: lxmf.delivery hash (hex) to public key. */
    val held = LinkedHashMap<String, ByteArray>()
    val remembered = mutableListOf<String>()

    fun know(identity: TestIdentity) { held[identity.hashHex] = identity.publicKey }

    /** What the router's callback hands over for [packed]: the unpacked message and the copy a creator keeps. */
    class Receipt(val unpacked: UnpackedGroupMessage, val packed: ByteArray?)

    override fun rememberKey(memberHex: String, publicKey: ByteArray): Boolean {
        if (publicKey.size != 64 || TestLxmf.deliveryHash(publicKey).toHex() != memberHex) return false
        held[memberHex] = publicKey
        remembered += memberHex
        return true
    }

    /**
     * Messages the native layer refuses whole (RFed SPEC §17.14 "Repeated
     * keys"): LXMF-rust decides that, with Python's key equality (pinned by the
     * JNI crate's test); this fake is told which.
     */
    val refused = mutableListOf<ByteArray>()

    /** The glue's `unpack_element`: 97..=2144 bytes. */
    override fun unpack(packed: ByteArray): GroupUnpack = when {
        packed.size <= GroupStatuses.PACKED_HEADER_LENGTH || packed.size > GroupStatuses.STATUS_PACKED_LIMIT -> GroupUnpack.NotLxmf
        refused.any { it.contentEquals(packed) } -> GroupUnpack.Refused
        else -> receipt(packed)?.unpacked?.let { GroupUnpack.Unpacked(it) } ?: GroupUnpack.NotLxmf
    }

    /** The glue's delivery callback for a message of any size: the copy is given only up to the limit, and only for an accept or leave. */
    fun receipt(packed: ByteArray): Receipt? {
        if (packed.size <= GroupStatuses.PACKED_HEADER_LENGTH) return null
        val destination = packed.copyOfRange(0, 16)
        val source = packed.copyOfRange(16, 32)
        val signature = packed.copyOfRange(32, 96)
        val payload = packed.copyOfRange(96, packed.size)
        val elements = runCatching {
            // [timestamp, title, content, fields, (stamp)]: the boundaries of each element.
            var at = if (payload[0].toInt() and 0xF0 == 0x90) 1 else return null
            val bounds = mutableListOf<IntRange>()
            repeat(payload[0].toInt() and 0x0F) {
                val e = Mp.end(payload, at)
                bounds += at until e
                at = e
            }
            bounds
        }.getOrNull() ?: return null
        if (elements.size < 4) return null
        val core = Mp.cat(byteArrayOf(0x94.toByte()), *elements.take(4).map { payload.copyOfRange(it.first, it.last + 1) }.toTypedArray())
        val hash = TestLxmf.sha256(destination, source, core)
        val signed = destination + source + core + hash
        val timestamp = java.nio.ByteBuffer.wrap(payload.copyOfRange(elements[0].first + 1, elements[0].first + 9)).double
        val fieldsRaw = payload.copyOfRange(elements[3].first, elements[3].last + 1)
        val fields = LxmfFields.decodeOrEmpty(fieldsRaw)

        val sourceHex = source.toHex()
        var verdict = held[sourceHex]?.let { if (TestLxmf.verify(it, signature, signed)) Signature.VALIDATED else Signature.INVALID }
            ?: Signature.SOURCE_UNKNOWN
        val action = fields.group(GroupEntry.ACTION)
        val isAcceptOrLeave = fields.group(GroupEntry.ID) != null && (action == Action.ACCEPT || action == Action.LEAVE)
        val carried = if (isAcceptOrLeave) boundKey(fields.group(GroupEntry.MEMBER_KEYS), sourceHex) else null
        if (carried != null && verdict == Signature.SOURCE_UNKNOWN) {
            verdict = if (TestLxmf.verify(carried, signature, signed)) Signature.VALIDATED else Signature.INVALID
        }
        val copy = if (isAcceptOrLeave && packed.size <= GroupStatuses.STATUS_PACKED_LIMIT) packed else null
        return Receipt(UnpackedGroupMessage(verdict, destination, source, hash, timestamp, carried, fieldsRaw), copy)
    }

    /** The key a message's GROUP_MEMBER_KEYS carries for its own [source], once bound (`carried_key` in group_status.rs). */
    private fun boundKey(entries: String?, source: String): ByteArray? {
        var entryForSource: String? = null
        for (entry in entries.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            val colon = entry.indexOf(':')
            if (colon < 0) continue
            val hash = entry.substring(0, colon).lowercase()
            val key = entry.substring(colon + 1)
            val wellFormed = Regex("^[0-9a-f]{32}$").matches(hash) && Regex("^[A-Za-z0-9+/]{86}==$").matches(key)
            if (wellFormed && hash == source) entryForSource = key
        }
        val key = entryForSource?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
        return key.takeIf { it.size == 64 && TestLxmf.deliveryHash(it).toHex() == source }
    }
}

/** What [MemoryGroupStatusStore] was asked to say about a member. */
data class Line(val chatId: String, val member: String, val joined: Boolean, val id: String, val timestampMs: Long, val otherDevice: Boolean = false)

/** [GroupStatusStore] in memory, with what Room's DAOs do. */
class MemoryGroupStatusStore : GroupStatusStore {
    val chats = LinkedHashMap<String, ChatEntity>()
    val memberRows = LinkedHashMap<String, MutableList<GroupMemberEntity>>()
    val lines = LinkedHashMap<String, Line>()
    val allowed = LinkedHashSet<String>()
    val keys = LinkedHashMap<String, ByteArray>()
    val copyRows = LinkedHashMap<Pair<String, String>, StatusCopy>()
    val heldRows = mutableListOf<HeldEntry>()
    private var nextId = 1L

    override suspend fun chatByGroupId(groupId: String) = chats.values.firstOrNull { it.groupIdHex == groupId && it.isGroup }
    override suspend fun chatById(chatId: String) = chats[chatId]?.takeIf { it.isGroup }
    override suspend fun members(chatId: String) = memberRows[chatId].orEmpty().sortedBy { it.destHashHex }

    override suspend fun setMemberStatus(chatId: String, memberHex: String, status: String) {
        val rows = memberRows[chatId] ?: return
        for (i in rows.indices) if (rows[i].destHashHex == memberHex) rows[i] = rows[i].copy(inviteStatus = status)
    }

    override suspend fun memberLine(chatId: String, memberHex: String, joined: Boolean, id: String, timestampMs: Long, otherDevice: Boolean) {
        lines[id] = Line(chatId, memberHex, joined, id, timestampMs, otherDevice)
    }

    override suspend fun allow(memberHex: String) { allowed += memberHex }
    override suspend fun keepKey(memberHex: String, publicKey: ByteArray) { keys[memberHex] = publicKey }
    override suspend fun copy(chatId: String, memberHex: String) = copyRows[chatId to memberHex]
    override suspend fun putCopy(chatId: String, memberHex: String, action: String, packed: ByteArray) {
        copyRows[chatId to memberHex] = StatusCopy(memberHex, action, packed)
    }
    override suspend fun copies(chatId: String) = copyRows.filterKeys { it.first == chatId }.values.sortedBy { it.memberHex }
    override suspend fun held() = heldRows.toList()
    override suspend fun heldCount() = heldRows.size
    override suspend fun heldCountFor(chatId: String, srcHex: String) = heldRows.count { it.chatId == chatId && it.srcHex == srcHex }
    override suspend fun heldFrom(srcHex: String) = heldRows.count { it.srcHex == srcHex }
    override suspend fun isHeld(msgHash: String) = heldRows.any { it.msgHash == msgHash }
    override suspend fun hold(entry: HeldEntry) {
        heldRows += HeldEntry(nextId++, entry.chatId, entry.srcHex, entry.action, entry.msgHash, entry.packed)
    }
    override suspend fun release(entry: HeldEntry) { heldRows.removeAll { it.id == entry.id } }

    fun statusOf(chatId: String, hex: String): String? = GroupMemberStatuses.statusOf(memberRows[chatId].orEmpty(), hex)
}

/** A status the creator sent. */
class Answer(val chatId: String, val to: String, val statuses: List<ByteArray>)

/**
 * A device holding the group of the vectors (or another), with the engine
 * wired to the doubles, and [receive] doing what `ChatRepository` does for a
 * group message the router delivers: the group rule (`onMessageReceived`), then
 * `handleGroupMessage`: the engine's `onKept`, then `takeDirect` for an accept or
 * leave and `takeStatuses` for a status. The wiring tests pin that the
 * repository calls them in that order.
 */
class GroupRig(
    val v: GroupStatusVectors,
    val me: String,
    val switched: Boolean = false,
    holds: Collection<String> = emptyList(),
    /**
     * When set, [me] is a distro D (the group's own address) and this is the
     * device that holds it, not listed (GROUPS-DISTRO-DESIGN.md §9.1); with
     * [siblings], the self addresses of the group.
     */
    val device: String? = null,
    val siblings: Set<String> = emptySet(),
) {
    val native = FakeGroupNative()
    val store = MemoryGroupStatusStore()
    val answers = mutableListOf<Answer>()
    val logs = mutableListOf<String>()
    /** The group's own address: the device, or D when [device] is set. */
    val self: String = v.hashOf(me)
    private val deviceHex: String = device ?: self
    val selves: Set<String> = setOf(self, deviceHex) + siblings
    val engine = GroupStatusEngine(
        store, native, { deviceHex },
        { chat, member, statuses -> answers += Answer(chat.id, member, statuses) },
        switched, { logs += it },
        selves = { GroupSelf(self, if (device != null) self else null, selves) },
        isSelfAnywhere = { it in selves },
    )

    /** What the rule dropped: the action and the source's name. */
    val dropped = mutableListOf<String>()

    /** The tally of the last `status` the rule let through. */
    var lastTally: StatusTally? = null

    init { holds.forEach { native.know(v.identities.getValue(it)) } }

    val chatId = "group_${v.groupId.take(16)}"

    /**
     * The group of the vectors, as this device holds it: [creator] listed and accepted, this device
     * [accepted] or only invited (pending), every other member invited.
     */
    fun hold(creator: String = v.creator, accepted: Boolean = true, groupId: String = v.groupId, members: List<String> = v.members, creatorRecorded: Boolean = true): ChatEntity {
        val id = "group_${groupId.take(16)}"
        val chat = ChatEntity(
            id = id, isGroup = true, name = "G", memberHashes = members.joinToString(",") { v.hashOf(it) },
            groupIdHex = groupId, creatorHex = if (creatorRecorded) v.hashOf(creator) else null,
        )
        store.chats[id] = chat
        store.memberRows[id] = members.map { name ->
            val hex = v.hashOf(name)
            val status = when {
                hex == self -> if (accepted) MemberStatus.ACCEPTED else MemberStatus.INVITED
                name == creator -> MemberStatus.ACCEPTED
                else -> MemberStatus.INVITED
            }
            GroupMemberEntity(chatId = id, destHashHex = hex, inviteStatus = status)
        }.toMutableList()
        return chat
    }

    fun status(name: String, chat: String = chatId): String? = store.statusOf(chat, v.hashOf(name))

    /** Every listed member's status, by name. */
    fun statuses(chat: String = chatId): Map<String, String?> = v.members.associateWith { status(it, chat) }

    /**
     * A packed message from a made or vector identity as the router would hand it over, through the group rule and the engine.
     * Returns whether it counted (an accept or leave), null when the rule dropped it or it is not one.
     */
    suspend fun receive(packed: ByteArray): Boolean? {
        val receipt = native.receipt(packed) ?: return null
        val u = receipt.unpacked
        val fields = LxmfFields.decodeOrEmpty(u.fieldsRaw)
        val groupId = fields.group(GroupEntry.ID) ?: return null
        val action = fields.group(GroupEntry.ACTION)
        val src = u.source.toHex()
        val chat = store.chatByGroupId(groupId)
        val members = chat?.let { store.members(it.id) }.orEmpty()
        // GROUPS-DISTRO-DESIGN.md §6.3: the first rules, before the action's own (D, when [me] is one, is the echo).
        DeliveryPolicy.firstRules(u.signature, src, fields.group(GroupEntry.SENDER), selves, if (device != null) setOf(self) else emptySet())?.let { why ->
            dropped += "$action from ${runCatching { v.nameOf(src) }.getOrDefault(src.take(8))}: $why"
            return null
        }
        val allowed = DeliveryPolicy.groupMessage(
            action = action,
            sourceAllowed = true,
            held = GroupMemberStatuses.held(chat != null, members, self),
            sourceStatus = GroupMemberStatuses.statusOf(members, src),
            namesOther = DeliveryPolicy.namesOther(fields.group(GroupEntry.SENDER), src),
            closed = false,
            signature = u.signature,
            sourceIsCreator = chat?.creatorHex != null && chat.creatorHex == src,
        )
        if (!allowed) {
            dropped += "$action from ${runCatching { v.nameOf(src) }.getOrDefault(src.take(8))}"
            return null
        }
        engine.onKept(src, action, u.signature, u.senderKey)
        return when (action) {
            Action.ACCEPT, Action.LEAVE -> engine.takeDirect(
                Arrival(u.hash.toHex(), src, chat!!, action, (u.timestamp * 1000).toLong(), u.signature, u.senderKey, receipt.packed)
            ) == GroupStatusEngine.Taken.COUNTED
            Action.STATUS -> { lastTally = engine.takeStatuses(chat!!, fields.groupStatuses()); null }
            else -> null
        }
    }

    /** What the answers were sent to, by name. */
    fun answeredNames(): List<String> = answers.map { v.nameOf(it.to) }
}
