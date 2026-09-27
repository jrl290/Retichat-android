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

    fun render(systemKind: String?, content: String, senderHashHex: String, names: NameBook): String =
        when (systemKind) {
            MEMBER -> "${names.contact(senderHashHex)} $content"
            else -> content
        }
}
