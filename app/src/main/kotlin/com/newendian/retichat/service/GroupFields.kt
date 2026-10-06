package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.bridge.GroupEntry
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.bridge.RetichatBridge

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
