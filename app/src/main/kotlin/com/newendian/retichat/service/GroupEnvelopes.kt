package com.newendian.retichat.service

import com.newendian.retichat.bridge.GroupEntry
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.data.repository.GroupStatuses

/**
 * The group entries of the control envelopes this device sends, apart from the
 * native message they are written on, so the JVM tests can check exactly what
 * each one carries in both forms (RFed-spec Group.md, "Member statuses and
 * keys", James 2026-10-06; DISPLAY_NAMES.md §10).
 *
 * * An **accept** and a **leave** (a decline is the very same message as a
 *   leave: James, 2026-10-02) carry exactly the sender's own key as
 *   GROUP_MEMBER_KEYS: one `hash:base64-public-key` entry, in the form an
 *   invite chunk uses, in the old field 0xA8 until the switch
 *   ([LxmfFields.GROUP_ENTRIES_IN_RETICHAT_FIELD]) and in key 9 of the
 *   Retichat field after it. A receiver that never heard of the sender binds
 *   it and verifies the message by it.
 * * A **status** (the creator's answer to an accept) carries GROUP_ID,
 *   GROUP_ACTION `status` and GROUP_SENDER the creator. Its GROUP_STATUSES
 *   (key 10) is written by [GroupFields.setStatuses], which has no old field.
 *
 * [GroupChatManager] writes these and nothing else for these three envelopes.
 */
object GroupEnvelopes {
    /** One str entry of an envelope. */
    class Entry(val entry: GroupEntry, val value: String) {
        override fun toString() = "$entry=$value"
    }

    /** The user's own GROUP_MEMBER_KEYS entry: [selfHex] and the 64-byte [publicKey]. */
    fun ownKeyEntry(selfHex: String, publicKey: ByteArray): String = GroupStatuses.keyEntry(selfHex, publicKey)

    /**
     * GROUP_ID, GROUP_ACTION `accept`, GROUP_SENDER [selfHex] and, last, the
     * user's own key ([ownKey], null only when the identity's key cannot be
     * read: then the message goes without it, as a build from before this
     * change sends it, rather than not at all).
     */
    fun accept(groupId: String, selfHex: String, ownKey: String?): List<Entry> =
        control(groupId, GroupChatManager.Action.ACCEPT, selfHex, ownKey)

    /** As [accept], for a leave, which a decline is too. */
    fun leave(groupId: String, selfHex: String, ownKey: String?): List<Entry> =
        control(groupId, GroupChatManager.Action.LEAVE, selfHex, ownKey)

    /** GROUP_ID, GROUP_ACTION `status` and GROUP_SENDER the creator [selfHex]. */
    fun status(groupId: String, selfHex: String): List<Entry> = listOf(
        Entry(GroupEntry.ID, groupId),
        Entry(GroupEntry.ACTION, GroupChatManager.Action.STATUS),
        Entry(GroupEntry.SENDER, selfHex),
    )

    private fun control(groupId: String, action: String, selfHex: String, ownKey: String?): List<Entry> = buildList {
        add(Entry(GroupEntry.ID, groupId))
        add(Entry(GroupEntry.ACTION, action))
        add(Entry(GroupEntry.SENDER, selfHex))
        if (ownKey != null) add(Entry(GroupEntry.MEMBER_KEYS, ownKey))
    }

    /** Write [entries] on [sink] in the form [inRetichatField] selects (the switch). */
    fun write(
        sink: GroupFields.Sink,
        entries: List<Entry>,
        inRetichatField: Boolean = LxmfFields.GROUP_ENTRIES_IN_RETICHAT_FIELD,
    ): Boolean = entries.all { GroupFields.set(sink, it.entry, it.value, inRetichatField) }

    /** Write [entries] on the native message [handle]. */
    fun write(handle: Long, entries: List<Entry>) {
        entries.forEach { GroupFields.set(handle, it.entry, it.value) }
    }
}
