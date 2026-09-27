package com.newendian.retichat.ui.channels

/**
 * What the Channel Info sheet shows and hands out for a channel, kept free of
 * Compose and Android so JVM tests can pin it.
 *
 * A channel is shared by its full name, `<root>.<name>` (e.g.
 * "abd77af5c72e6b5b.general"): the channel key derives from those bytes, so
 * for a private channel the full name is the invite. The channel hash cannot
 * be used to join, so it is only secondary text.
 */
object ChannelShare {
    const val PRIVATE_HINT = "Share the full name to invite someone. Anyone with it can read and post."
    const val PUBLIC_HINT = "Anyone who knows the name can join."

    /**
     * Exactly the text to copy or share: the stored full name with no "#"
     * display prefix and no surrounding whitespace.
     */
    fun shareText(channelName: String): String = channelName.trim().trimStart('#').trim()

    /**
     * True when the name has a root other than [ChannelNameForm.PUBLIC_ROOT].
     * A name with no root (no ".") has no secret part, so it is public.
     */
    fun isPrivate(channelName: String): Boolean {
        val name = shareText(channelName)
        val dot = name.indexOf('.')
        if (dot <= 0) return false
        return name.substring(0, dot) != ChannelNameForm.PUBLIC_ROOT
    }

    /**
     * The one-line hint under the name, or null while the name is not known
     * (the channel record has not loaded): an empty name has no root, and
     * calling it public would describe a private channel as open to anyone.
     */
    fun hint(channelName: String): String? = when {
        shareText(channelName).isEmpty() -> null
        isPrivate(channelName) -> PRIVATE_HINT
        else -> PUBLIC_HINT
    }
}
