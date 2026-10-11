package com.newendian.retichat.names

/**
 * System messages keep the hash of the member they are about
 * (`senderHashHex`) and the text without the name; the name is resolved when
 * the row is shown (DISPLAY_NAMES.md §5.3), so a name learned later, or a
 * rename, reaches old system messages too.
 */
object SystemText {
    /** `messages.systemKind`: prefix the resolved name of `senderHashHex` to `content`. */
    const val MEMBER = "member"

    /**
     * `messages.systemKind`: `senderHashHex` is another device of the user's
     * distro acting as its own listed entry (GROUPS-DISTRO-DESIGN.md §9.1):
     * "Your other device" before `content`, the same words on iOS and the web
     * (alignment decision 3, 2026-10-11). Not "You …": that reads as the
     * user's own accept or leave on this device.
     */
    const val OTHER_DEVICE = "other_device"

    fun render(systemKind: String?, content: String, senderHashHex: String, names: NameBook): String =
        when (systemKind) {
            MEMBER -> "${names.contact(senderHashHex)} $content"
            OTHER_DEVICE -> "Your other device $content"
            else -> content
        }
}
