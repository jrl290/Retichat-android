package com.newendian.retichat.names

/**
 * The one resolver every surface uses (DISPLAY_NAMES.md §5.3): a snapshot of
 * the contacts' name slots, keyed by lowercase hash hex, read live from Room
 * by whoever shows names. Nothing stores what this returns.
 */
class NameBook(
    private val contacts: Map<String, ContactNames> = emptyMap(),
    /** This device's lxmf.delivery hash; its own rows read "You" where a list shows it. */
    private val selfHex: String = "",
) {
    fun names(hashHex: String): ContactNames? = contacts[hashHex.lowercase()]

    /** A contact, group member or DM peer: `localName ?? messageName ?? announceName ?? shortHash`. */
    fun contact(hashHex: String): String = DisplayNames.contact(names(hashHex), hashHex)

    /** Like [contact], but this device reads "You" (member lists). */
    fun member(hashHex: String): String =
        if (selfHex.isNotEmpty() && hashHex.equals(selfHex, ignoreCase = true)) "You" else contact(hashHex)

    /** A channel post, with that poster's channel name in this channel (or null). */
    fun channelPost(hashHex: String, channelName: String?): ChannelLabel =
        DisplayNames.channelPost(channelName, names(hashHex), hashHex)

    companion object {
        val EMPTY = NameBook()
    }
}
