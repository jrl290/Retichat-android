package com.newendian.retichat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The QR screen's Copy Hash button changed width when its text was swapped
 * for "Copied!" (L83, as on iOS). CopyLabel lays out both texts and shows one,
 * so the button keeps the width of the wider; every copy button uses it. It
 * needs a running Compose UI to measure, so it is checked in the source, as
 * ChatListNewTopContractTest does.
 */
class CopyLabelContractTest {
    private val components = File("src/main/kotlin/com/newendian/retichat/ui/components/GlassComponents.kt").readText()
    private val qr = File("src/main/kotlin/com/newendian/retichat/ui/contacts/QrCodeScreen.kt").readText()

    @Test
    fun theLabelLaysOutBothTextsAndShowsOne() {
        val label = components.substringAfter("fun CopyLabel(title: String, copied: Boolean) {", "")
        assertTrue("CopyLabel is in GlassComponents.kt", label.isNotEmpty())
        assertTrue("both texts are laid out, so the button is as wide as the wider",
            label.contains("Text(title, modifier = Modifier.alpha(if (copied) 0f else 1f))")
                && label.contains("Text(\"Copied!\", modifier = Modifier.alpha(if (copied) 1f else 0f))"))
        assertTrue("TalkBack reads only the one shown",
            label.contains("clearAndSetSemantics { contentDescription = if (copied) \"Copied!\" else title }"))
    }

    @Test
    fun everyCopyButtonUsesIt() {
        assertTrue("the QR screen's Copy Hash", qr.contains("CopyLabel(\"Copy Hash\", copied)"))
        val swap = Regex("""Text\(if \(\w+\) "Copied!"""")
        val swapping = File("src/main/kotlin").walk()
            .filter { it.extension == "kt" && swap.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals("no Text is swapped for \"Copied!\"", emptyList<String>(), swapping)
    }
}
