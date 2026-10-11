package com.newendian.retichat.service

/**
 * The group sync G (RFed SPEC §17.14; GROUPS-DISTRO-DESIGN.md §10, §11): what
 * one device of a distro D tells the others it did as D in a group, from D to
 * D, signed by D, through D's fan-out. The bytes are written and read by the
 * shared Rust layer (`set_group_sync_fields`, `read_group_sync`; rmpv, never a
 * bin of packed msgpack, CHECK_THESE_THINGS_FIRST §11); this is the client's
 * part: the JSON the bridge takes and gives, the receive rules 1-4 and 6 of
 * §11.1 (rule 0, the repeat, is the seen store's), and what applying one means
 * for a group as this device holds it (§11.2). Pure logic, so the JVM tests
 * run it; [ChatRepository] does what it decides.
 */
data class GroupSync(
    val op: String,
    val groupId: String,
    val atMs: Long,
    /** create, accept: GROUP_NAME as the group holds it. */
    val name: String? = null,
    /** create, accept: the GROUP_MEMBERS csv exactly as the invite carried it (create: as this device wrote it). */
    val members: String? = null,
    /** create, accept: GROUP_MEMBER_KEYS entries, one per listed member whose key the device holds. */
    val keys: List<String>? = null,
    /** accept: the invite's authenticated source, as recorded. */
    val creator: String? = null,
    /** post: the post's text unchanged; attachments are not copied (§17.11). */
    val text: String? = null,
    /** Every op: the D-groups the device holds as left by D, most recent first, at most [DistroGroupSync.LEFT_MAX]. */
    val left: List<String> = emptyList(),
) {
    /**
     * The JSON the writer takes (`RetichatBridge.messageSetDistroGroupSync`,
     * LXMF-rust `GroupSync::from_json`): every key, null where the op does not
     * carry it. Written by hand: org.json is not on the JVM the tests run on, and
     * the bridge parses it with serde_json, which reads this exactly.
     */
    fun toJson(): String {
        val describes = op == DistroGroupSync.CREATE || op == DistroGroupSync.ACCEPT
        return buildString {
            append("{\"op\":").append(Json.str(op))
            append(",\"group_id\":").append(Json.str(groupId))
            append(",\"at_ms\":").append(atMs)
            append(",\"name\":").append(if (describes) Json.str(name ?: "") else "null")
            append(",\"members\":").append(if (describes) Json.str(members ?: "") else "null")
            append(",\"keys\":").append(if (describes) Json.list(keys ?: emptyList()) else "null")
            append(",\"creator\":").append(if (op == DistroGroupSync.ACCEPT && creator != null) Json.str(creator) else "null")
            append(",\"text\":").append(if (op == DistroGroupSync.POST) Json.str(text ?: "") else "null")
            append(",\"left\":").append(Json.list(left))
            append('}')
        }
    }

    /** A short name for the log: `the leave group sync of 9f8e7d6c`. */
    val label: String get() = "the $op group sync of ${groupId.take(8)} (§17.14)"

    private object Json {
        fun str(s: String): String = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append(String.format("\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

        fun list(items: List<String>): String = items.joinToString(",", "[", "]") { str(it) }
    }
}

/**
 * A received group sync as the unwrap reports it (GROUPS-DISTRO-DESIGN.md §7,
 * `group_sync`): [by] (`fields[0xFD]` lowercased, empty when absent), and either
 * [sync] or the [problem] that fails §11.1 rule 4 (the shared reader's, or this
 * client's when the JSON itself is not what the bridge writes).
 */
class GroupSyncRead(val by: String, val problem: String?, val sync: GroupSync?)

object DistroGroupSync {
    /** `fields[0xFB]` of a group sync. */
    const val TYPE = "rfed.distro.group"
    const val CREATE = "create"
    const val ACCEPT = "accept"
    const val LEAVE = "leave"
    const val POST = "post"
    val OPS = setOf(CREATE, ACCEPT, LEAVE, POST)

    /** `left` holds at most 32 ids (§10.2), and the per-D list of them as many. */
    const val LEFT_MAX = 32

    /** 2^53 - 1: `at_ms` is exact in every JSON reader. */
    const val MAX_AT_MS = 9_007_199_254_740_991L

    /** The outbox id of an owed G (§10.5): each is owed on its own, under its LXMF hash. */
    fun entryId(hash: String): String = "group:$hash"

    /** The seen key of a G (§6.2): its LXMF hash, never `src:ts`. */
    fun seenKey(hash: String): String = "gsync:$hash"

    /** The seen key of a group message on the distro path (§6.2). */
    fun messageSeenKey(hash: String): String = "grp:$hash"

    /** Rule 0's record of the seen keys (on the device, UserPreferences' distro-seen list). */
    interface SeenRecord {
        fun has(key: String): Boolean
        fun mark(key: String)
    }

    /**
     * Whether a G or a group message that came to [outcome] has its seen key
     * recorded: only when it was kept (applied, stored, an own echo, or
     * nothing to do), never when it was held or dropped (§6.2), so a later
     * copy, or its replay, is judged again. A forged G has the LXMF hash of D's
     * own (the hash does not cover the signature): recorded, it would make the
     * device skip D's real one.
     */
    fun recordsSeen(outcome: com.newendian.retichat.data.repository.ChatRepository.DistroGroupOutcome): Boolean =
        outcome == com.newendian.retichat.data.repository.ChatRepository.DistroGroupOutcome.KEPT

    /**
     * Rule 0 and the record, for a G ([seenKey]) and for a group message
     * through the distro ([messageSeenKey]): a [key] already recorded is
     * skipped (null, and [judge] is not asked); otherwise [judge] decides, and
     * [key] is recorded only as [recordsSeen] says. The caller holds the lock
     * that makes the check and the record one step.
     */
    suspend fun throughRule0(
        key: String,
        record: SeenRecord,
        judge: suspend () -> com.newendian.retichat.data.repository.ChatRepository.DistroGroupOutcome,
    ): com.newendian.retichat.data.repository.ChatRepository.DistroGroupOutcome? {
        if (record.has(key)) return null
        val outcome = judge()
        if (recordsSeen(outcome)) record.mark(key)
        return outcome
    }

    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun isHash(value: String?): Boolean = value != null && HEX64.matches(value)

    // ---- Reading the unwrap's group_sync object ----

    /**
     * The `group_sync` object of the unwrap JSON, through [get] (a JSON object's
     * keys: strings, numbers, booleans, lists, null). Never throws: anything that
     * is not the shape §7 gives is a [GroupSyncRead.problem], and the G is dropped
     * by rule 4 with it.
     */
    fun read(get: (String) -> Any?): GroupSyncRead {
        val by = (get("by") as? String)?.lowercase() ?: ""
        (get("problem") as? String)?.let { return GroupSyncRead(by, it, null) }
        fun bad(why: String) = GroupSyncRead(by, "the unwrap's group_sync $why", null)
        val op = get("op") as? String ?: return bad("has no op")
        if (op !in OPS) return bad("op $op is not create, accept, leave or post")
        val groupId = (get("group_id") as? String)?.lowercase()?.takeIf { HEX32.matches(it) } ?: return bad("group_id is not 32 hex")
        val atMs = integer(get("at_ms"))?.takeIf { it in 0..MAX_AT_MS } ?: return bad("at_ms is not an integer from 0 to 2^53 - 1")
        val left = strings(get("left"))?.map { it.lowercase() } ?: return bad("left is not an array of strings")
        if (left.size > LEFT_MAX || left.any { !HEX32.matches(it) }) return bad("left is not at most $LEFT_MAX ids of 32 hex")
        val describes = op == CREATE || op == ACCEPT
        var sync = GroupSync(op, groupId, atMs, left = left)
        if (describes) {
            val name = get("name") as? String ?: return bad("has no name")
            val members = get("members") as? String ?: return bad("has no members")
            val keys = strings(get("keys")) ?: return bad("keys is not an array of strings")
            sync = sync.copy(name = name, members = members, keys = keys)
        }
        if (op == ACCEPT) {
            val creator = (get("creator") as? String)?.lowercase()?.takeIf { HEX32.matches(it) } ?: return bad("creator is not 32 hex")
            sync = sync.copy(creator = creator)
        }
        if (op == POST) {
            val text = get("text") as? String ?: return bad("has no text")
            sync = sync.copy(text = text)
        }
        return GroupSyncRead(by, null, sync)
    }

    /** A JSON number that is an integer (org.json gives Integer or Long; a Double only when integral). */
    private fun integer(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.takeIf { it == Math.floor(it) && !it.isInfinite() && it <= MAX_AT_MS.toDouble() }?.toLong()
        is Number -> value.toLong()
        else -> null
    }

    private fun strings(value: Any?): List<String>? {
        val list = value as? List<*> ?: return null
        return list.map { it as? String ?: return null }
    }

    // ---- §11.1 rules 1-4 ----

    sealed class Disposition {
        /** Rule 1: the unwrapped source is not D. Ignored, with a log line, not recorded. */
        data class NotFromDistro(val source: String) : Disposition()
        /** Rule 2: the signature does not validate under D's key. Dropped with a log line, not recorded. */
        object Forged : Disposition()
        /** Rule 3: this device's own G back from the fan-out. Dropped silently, recorded as seen. */
        object OwnEcho : Disposition()
        /** Rule 4: the form fails. Dropped with a log line, not recorded. [sibling] was learned at rule 3. */
        data class BadForm(val problem: String, val sibling: String?) : Disposition()
        /** Passed rules 1-4: applied (rules 5 and 6). [sibling] is the sending device when it is not this one. */
        data class Apply(val sync: GroupSync, val sibling: String?) : Disposition()
    }

    /**
     * Rules 1-4 of §11.1 for a G from [sourceHex] (the unwrapped LXMF source),
     * [signatureValidated] the unwrap's verdict (checked with the distro key it
     * holds, over the payload as received, as §17.11 rule 2), [distroHex] the D
     * this device holds and [deviceHex] this device. Rule 3 teaches the sending
     * device's address (§9.1): every other `by` of a D-signed G is a sibling.
     */
    fun disposition(sourceHex: String, distroHex: String?, signatureValidated: Boolean, read: GroupSyncRead, deviceHex: String): Disposition {
        if (distroHex == null || sourceHex != distroHex) return Disposition.NotFromDistro(sourceHex)
        if (!signatureValidated) return Disposition.Forged
        if (read.by == deviceHex) return Disposition.OwnEcho
        val sibling = siblingOf(read.by, deviceHex)
        val sync = read.sync ?: return Disposition.BadForm(read.problem ?: "it has no readable form", sibling)
        return Disposition.Apply(sync, sibling)
    }

    /** A `fields[0xFD]` that names another device: 32 hex, not this device (§9.1 "Known siblings"). */
    fun siblingOf(by: String?, deviceHex: String): String? =
        by?.lowercase()?.takeIf { HEX32.matches(it) && it != deviceHex }

    // ---- §11.2 Apply ----

    /** The group G names, as this device holds it. */
    sealed class Local {
        object NotHeld : Local()
        /** Recorded closed (declined, left, deleted, or a leave G for a group not held): final. */
        object Closed : Local()
        /**
         * Held here: [ownIsD] the group's own address is D (a D-group of this D),
         * [joined] its own address's row accepted, [creator] the recorded creator,
         * [members] the hashes its list holds.
         */
        data class Held(val ownIsD: Boolean, val joined: Boolean, val creator: String?, val members: Set<String>, val inviter: String? = null) : Local()
    }

    sealed class Effect {
        /**
         * Hold the group from G: list = `members`, creator [creator], own address D,
         * joined. When [discarded] a local pending invite was there first: it is
         * discarded without sending anything (the user's own sync outranks anyone
         * else's invite for its id), with a log line naming its inviter.
         */
        data class HoldFromG(val creator: String, val discarded: Boolean) : Effect()
        /** An accept G that describes the pending invite held here: joined, as the user's own accept would make it, without sending. */
        object JoinPending : Effect()
        /** A leave G for a group not held here: its id is recorded closed (a tombstone). */
        object RecordClosed : Effect()
        /** A leave G for a group held here (pending or joined): closed as the local decline or leave closes it, without sending. */
        object Close : Effect()
        /** A post G in a joined group: stored as the user's own outgoing post, SENT, no notification. */
        object StorePost : Effect()
        /** A post G for a group pending or not held here: held (§6.5), not recorded as seen. */
        object HoldPost : Effect()
        /** A post G for a closed group. */
        object DropPost : Effect()
        /** Already held, or closed (final): nothing changes. */
        object Nothing : Effect()
        /** Rule 5: the group is held here with another own address (held at upgrade as the device). Its `left` still applies. */
        object IgnoreOwnAddress : Effect()
    }

    /** What [sync] does to the group as held here ([local]) — §11.1 rule 5 and the table of §11.2. */
    fun effect(sync: GroupSync, local: Local, distroHex: String): Effect {
        if (local is Local.Closed) return if (sync.op == POST) Effect.DropPost else Effect.Nothing
        if (local is Local.Held && !local.ownIsD) return Effect.IgnoreOwnAddress
        return when (sync.op) {
            CREATE -> when (local) {
                Local.NotHeld -> Effect.HoldFromG(distroHex, discarded = false)
                is Local.Held -> if (local.joined) Effect.Nothing else Effect.HoldFromG(distroHex, discarded = true)
                Local.Closed -> Effect.Nothing
            }
            ACCEPT -> {
                val creator = sync.creator ?: return Effect.Nothing
                when (local) {
                    Local.NotHeld -> Effect.HoldFromG(creator, discarded = false)
                    is Local.Held -> when {
                        local.joined -> Effect.Nothing
                        local.creator == creator && local.members == listed(sync.members) -> Effect.JoinPending
                        else -> Effect.HoldFromG(creator, discarded = true)
                    }
                    Local.Closed -> Effect.Nothing
                }
            }
            LEAVE -> when (local) {
                Local.NotHeld -> Effect.RecordClosed
                is Local.Held -> Effect.Close
                Local.Closed -> Effect.Nothing
            }
            POST -> when (local) {
                Local.NotHeld -> Effect.HoldPost
                is Local.Held -> if (local.joined) Effect.StorePost else Effect.HoldPost
                Local.Closed -> Effect.DropPost
            }
            else -> Effect.Nothing
        }
    }

    /** What an id in a G's `left` does here (§11.2, "`left` is applied after the op"). */
    enum class LeftEffect {
        /** Held here as a D-group: applied as a leave G (closed, removed, nothing sent). */
        CLOSE,
        /** Not held here: recorded closed. */
        RECORD_CLOSED,
        /** Already closed: nothing changes, and it still goes in this device's own `left` list. */
        ALREADY_CLOSED,
        /** Held here with another own address: ignored. */
        IGNORE,
    }

    fun leftEffect(local: Local): LeftEffect = when (local) {
        Local.NotHeld -> LeftEffect.RECORD_CLOSED
        Local.Closed -> LeftEffect.ALREADY_CLOSED
        is Local.Held -> if (local.ownIsD) LeftEffect.CLOSE else LeftEffect.IGNORE
    }

    /**
     * This device's `left` list for D with [ids] recorded as the most recent
     * (first), each once, at most [LEFT_MAX], the oldest forgotten first (§10.2).
     */
    fun recordLeft(list: List<String>, ids: List<String>): List<String> {
        val fresh = ids.map { it.lowercase() }.distinct()
        return (fresh + list.filter { it !in fresh }).take(LEFT_MAX)
    }

    /** The members a csv lists, by the invite's own parser. */
    fun listed(csv: String?): Set<String> = com.newendian.retichat.data.repository.DeliveryPolicy.members(csv).toSet()

    // ---- Routing on the distro path (§6.1) ----

    sealed class Route {
        /** Step 1: a group sync (`fields[0xFB]` = [TYPE]). */
        data class Sync(val read: GroupSyncRead) : Route()
        /** Step 2: a group message with a well-formed id. */
        data class Message(val groupId: String) : Route()
        /** Step 2: a GROUP_ID that is not 32 hex: dropped in the group branch with a log line, never a DM. */
        object BadGroupId : Route()
        /** Step 3: everything else, as before, under the `src:ts` key. */
        object Other : Route()
    }

    /**
     * The branch an unwrapped blob takes after the §17.12 channel sync (§6.1):
     * the group sync first, then a group message (routed by `has_group_id`, not
     * by the id: a malformed id is dropped in the group branch), then the rest.
     */
    fun route(groupSync: GroupSyncRead?, hasGroupId: Boolean, groupId: String?): Route = when {
        groupSync != null -> Route.Sync(groupSync)
        hasGroupId -> groupId?.lowercase()?.takeIf { HEX32.matches(it) }?.let { Route.Message(it) } ?: Route.BadGroupId
        else -> Route.Other
    }
}
