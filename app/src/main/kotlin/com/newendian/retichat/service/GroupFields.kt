package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.bridge.GroupEntry
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.repository.Reactions

/**
 * The one place group entries are written to an outbound message
 * (LXMF-rust/DISPLAY_NAMES.md §10). [LxmfFields.GROUP_ENTRIES_IN_RETICHAT_FIELD]
 * picks the form: false writes the old top-level field (0xA0-0xA8), which
 * released apps read; true writes key 1-9 of the Retichat field 0xD1 through
 * the native setter, which merges it beside the router's name entry.
 *
 * Key 10, a `status`'s GROUP_STATUSES (RFed-spec Group.md, "Member statuses and
 * keys"), is the one entry that has no old field: [setStatuses] writes it into
 * the Retichat field in both forms, even before the switch.
 */
object GroupFields {
    private const val TAG = "GroupFields"

    /** Where an entry goes: a native message, or a recorder in tests. */
    interface Sink {
        fun addFieldString(key: Int, value: String): Boolean
        fun addFieldBool(key: Int, value: Boolean): Boolean
        fun setRetichatString(key: Int, value: String): Boolean
        fun setRetichatBool(key: Int, value: Boolean): Boolean

        /** Key 10 of the Retichat field: an array of bin, whatever the switch says. */
        fun setRetichatStatuses(elements: List<ByteArray>): Boolean
    }

    /** The native message [handle]. */
    class Message(private val handle: Long) : Sink {
        override fun addFieldString(key: Int, value: String) = RetichatBridge.messageAddFieldString(handle, key, value)
        override fun addFieldBool(key: Int, value: Boolean) = RetichatBridge.messageAddFieldBool(handle, key, value)
        override fun setRetichatString(key: Int, value: String) = RetichatBridge.messageSetRetichatString(handle, key, value)
        override fun setRetichatBool(key: Int, value: Boolean) = RetichatBridge.messageSetRetichatBool(handle, key, value)
        override fun setRetichatStatuses(elements: List<ByteArray>) = RetichatBridge.messageSetRetichatStatuses(handle, elements)
    }

    /** Set the str entry [entry] on message [handle]. */
    fun set(handle: Long, entry: GroupEntry, value: String): Boolean = report(entry, set(Message(handle), entry, value))

    /** Set the bool entry [entry] ([GroupEntry.RELAY_DONE]) on message [handle]. */
    fun set(handle: Long, entry: GroupEntry, value: Boolean): Boolean = report(entry, set(Message(handle), entry, value))

    /**
     * Set GROUP_STATUSES (key 10) on message [handle]: [elements] are the
     * members' own packed accepts and leaves exactly as the creator received
     * them, an empty list a valid answer. Always in the Retichat field.
     */
    fun setStatuses(handle: Long, elements: List<ByteArray>): Boolean {
        val ok = setStatuses(Message(handle), elements)
        if (!ok) Log.w(TAG, "GROUP_STATUSES not set: ${RetichatBridge.lastError()}")
        return ok
    }

    fun setStatuses(sink: Sink, elements: List<ByteArray>): Boolean = sink.setRetichatStatuses(elements)

    /**
     * Set GROUP_POST_ID (key 11, REACTIONS-DESIGN.md §4) on message [handle]:
     * the Group post's id, 32 hex, the same on every copy of it and on a relay
     * request for it and its relayed copies. Always in the Retichat field: it
     * has no old top-level field, so a released build, which reads only those,
     * skips it.
     */
    fun setPostId(handle: Long, postId: String): Boolean {
        val ok = setPostId(Message(handle), postId)
        if (!ok) Log.w(TAG, "GROUP_POST_ID not set: ${RetichatBridge.lastError()}")
        return ok
    }

    fun setPostId(sink: Sink, postId: String): Boolean = sink.setRetichatString(LxmfFields.RF_GROUP_POST_ID, postId)

    /**
     * The fields of the Distro Sync of a send M (RFed SPEC §17.11) on [sink]:
     * LXMF's custom pair with the sent type, the recipient (0xFC) and this
     * device's address (0xFD), as before; and (REACTIONS-DESIGN.md §5.2) M's
     * LXMF hash [sentHash] as key 12 (SENT_HASH) when it is known, and M's own
     * field 0x40 when M is a [reaction], set through [setReaction]. False when
     * any of it could not be set: the copy is then not made.
     */
    fun setSentCopy(
        sink: Sink,
        recipientHex: String,
        deviceHex: String,
        sentHash: String?,
        reaction: Reactions.Outgoing?,
        setReaction: (ByteArray, String) -> Boolean,
    ): Boolean =
        sink.addFieldString(LxmfFields.FIELD_CUSTOM_TYPE, LxmfFields.DISTRO_SENT_TYPE) &&
            sink.addFieldString(LxmfFields.FIELD_CUSTOM_DATA, recipientHex) &&
            sink.addFieldString(LxmfFields.FIELD_CUSTOM_META, deviceHex) &&
            (sentHash == null || sink.setRetichatString(LxmfFields.RF_SENT_HASH, sentHash)) &&
            (reaction == null || setReaction(reaction.to, reaction.content))

    fun set(
        sink: Sink,
        entry: GroupEntry,
        value: String,
        inRetichatField: Boolean = LxmfFields.GROUP_ENTRIES_IN_RETICHAT_FIELD,
    ): Boolean {
        require(entry.type == GroupEntry.Type.STR) { "$entry is not a str entry" }
        return if (inRetichatField) sink.setRetichatString(entry.key, value)
        else sink.addFieldString(entry.legacyField, value)
    }

    fun set(
        sink: Sink,
        entry: GroupEntry,
        value: Boolean,
        inRetichatField: Boolean = LxmfFields.GROUP_ENTRIES_IN_RETICHAT_FIELD,
    ): Boolean {
        require(entry.type == GroupEntry.Type.BOOL) { "$entry is not a bool entry" }
        return if (inRetichatField) sink.setRetichatBool(entry.key, value)
        else sink.addFieldBool(entry.legacyField, value)
    }

    private fun report(entry: GroupEntry, ok: Boolean): Boolean {
        if (!ok) Log.w(TAG, "group entry $entry not set: ${RetichatBridge.lastError()}")
        return ok
    }
}
