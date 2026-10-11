package com.newendian.retichat.service

/**
 * The keys the distro unwrap gained for groups (GROUPS-DISTRO-DESIGN.md §7),
 * read from its JSON through [read]'s accessor (a JSON object's keys: strings,
 * numbers, booleans, lists, maps, null). Read defensively: a key that is absent
 * or of another type reads as absent, and never throws.
 *
 * - [hash]: the LXMF message hash, 64 lowercase hex; a group message and a
 *   group sync are keyed by it (§6.2).
 * - [hasGroupId]: a GROUP_ID entry in either form, of any type, and no distro
 *   marker: this, not [groupId], routes a message to the group branch (§6.1).
 * - [groupId]: the id when it is 32 hex, lowercased; [groupAction] and
 *   [groupName] as sent.
 * - [groupSync]: non-null exactly for `fields[0xFB]` = `rfed.distro.group`.
 * - [senderKey]: the 64-byte key an accept or leave carries for its own
 *   sender, when it binds to the source; [packed] the message exactly as
 *   received (`dest | src | sig | payload`), for the creator's kept copy.
 */
class DistroGroupKeys(
    val hash: String?,
    val hasGroupId: Boolean,
    val groupId: String?,
    val groupAction: String?,
    val groupName: String?,
    val groupSync: GroupSyncRead?,
    val senderKey: ByteArray?,
    val packed: ByteArray?,
) {
    companion object {
        private val HEX128 = Regex("^[0-9a-f]{128}$")

        fun read(get: (String) -> Any?): DistroGroupKeys {
            val hash = (get("hash") as? String)?.lowercase()?.takeIf { DistroGroupSync.isHash(it) }
            val sync = (get("group_sync") as? Map<*, *>)?.let { map -> DistroGroupSync.read { map[it] } }
            val senderKey = (get("sender_key") as? String)?.lowercase()?.takeIf { HEX128.matches(it) }
                ?.let { DistroCodec.hexToBytes(it) }?.takeIf { it.size == 64 }
            val packed = (get("packed") as? String)?.let {
                runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
            }?.takeIf { it.isNotEmpty() }
            return DistroGroupKeys(
                hash = hash,
                hasGroupId = get("has_group_id") == true,
                groupId = (get("group_id") as? String)?.lowercase(),
                groupAction = get("group_action") as? String,
                groupName = get("group_name") as? String,
                groupSync = sync,
                senderKey = senderKey,
                packed = packed,
            )
        }
    }
}
