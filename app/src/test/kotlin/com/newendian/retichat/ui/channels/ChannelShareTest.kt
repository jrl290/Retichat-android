package com.newendian.retichat.ui.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChannelShareTest {
    private val privateName = "abd77af5c72e6b5b.general"

    private val sheet: String by lazy {
        File("src/main/kotlin/com/newendian/retichat/ui/channels/ChannelInfoSheet.kt").readText()
    }

    // --- what is copied / shared ---

    @Test
    fun shareTextIsExactlyTheFullName() {
        assertEquals(privateName, ChannelShare.shareText(privateName))
        assertEquals("public.news", ChannelShare.shareText("public.news"))
    }

    @Test
    fun shareTextDropsHashPrefixAndWhitespace() {
        assertEquals(privateName, ChannelShare.shareText("#$privateName"))
        assertEquals(privateName, ChannelShare.shareText("  # $privateName \n"))
    }

    // --- private / public hint ---

    @Test
    fun customRootIsPrivate() {
        assertTrue(ChannelShare.isPrivate(privateName))
        assertTrue(ChannelShare.isPrivate("4cdc4115.nametest"))
        assertTrue(ChannelShare.isPrivate("myteam.ops.night"))
        assertEquals(
            "Share the full name to invite someone. Anyone with it can read and post.",
            ChannelShare.hint(privateName),
        )
    }

    @Test
    fun publicRootAndRootlessNamesArePublic() {
        assertFalse(ChannelShare.isPrivate("public.news"))
        assertFalse(ChannelShare.isPrivate("#public.news"))
        assertFalse(ChannelShare.isPrivate("legacy"))
        assertFalse(ChannelShare.isPrivate(".news"))
        assertEquals("Anyone who knows the name can join.", ChannelShare.hint("public.news"))
    }

    @Test
    fun noHintWhileTheNameIsUnknown() {
        // The sheet gets "" until the channel record loads; a private channel
        // must not be shown the public hint in that window.
        assertNull(ChannelShare.hint(""))
        assertNull(ChannelShare.hint("  # "))
    }

    // --- the sheet's wiring (source-level: Compose UI is not run on the JVM) ---

    @Test
    fun sheetShowsTheFullNameSelectableAndUntruncated() {
        assertFalse("the name must not carry a # the user would copy", sheet.contains("\"#\${channelName}\""))
        val nameBlock = sheet.substringAfter("SelectionContainer {").substringBefore("}")
        assertTrue(nameBlock.contains("shareName,"))
        assertFalse(nameBlock.contains("maxLines"))
        assertFalse(nameBlock.contains("Ellipsis"))
        assertTrue(sheet.contains("val shareName = ChannelShare.shareText(channelName)"))
    }

    @Test
    fun sheetKeepsTheHashAsSecondaryText() {
        assertTrue(sheet.contains("channelId,\n"))
    }

    @Test
    fun sheetShowsTheHintUnderTheName() {
        assertTrue(sheet.contains("ChannelShare.hint(shareName)?.let"))
        assertTrue(sheet.indexOf("ChannelShare.hint(shareName)") > sheet.indexOf("SelectionContainer {"))
    }

    @Test
    fun copyNamePutsExactlyTheShareTextOnTheClipboard() {
        assertTrue(sheet.contains("Text(\"Copy name\")"))
        assertTrue(sheet.contains("copyChannelName(context, shareName)"))
        assertTrue(sheet.contains("ClipData.newPlainText(\"Channel name\", name)"))
        // Android 13+ confirms clipboard writes itself; a Toast there doubles it.
        assertTrue(sheet.contains("Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2"))
    }

    @Test
    fun shareOffersTheNameThroughTheSystemChooser() {
        assertTrue(sheet.contains("shareChannelName(context, shareName)"))
        assertTrue(sheet.contains("Intent(Intent.ACTION_SEND)"))
        assertTrue(sheet.contains("putExtra(Intent.EXTRA_TEXT, name)"))
        assertTrue(sheet.contains("Intent.createChooser(send,"))
    }
}
