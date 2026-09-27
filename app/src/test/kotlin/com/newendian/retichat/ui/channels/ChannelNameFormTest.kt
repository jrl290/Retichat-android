package com.newendian.retichat.ui.channels

import com.newendian.retichat.ui.channels.ChannelNameForm.Fields
import com.newendian.retichat.ui.channels.ChannelNameForm.RootProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

class ChannelNameFormTest {
    private val root = "0123456789abcdef"

    private fun privateInput(previous: String, input: String, r: String = root) =
        ChannelNameForm.onNameInput(previous, input, isPrivate = true, root = r)

    private fun publicInput(previous: String, input: String) =
        ChannelNameForm.onNameInput(previous, input, isPrivate = false, root = root)

    // --- 1. editable, filtered root ---

    @Test
    fun rootFilterLowercasesAndDropsDotsAndSymbols() {
        assertEquals("abc-12", ChannelNameForm.filterRoot("AbC-1.2"))
        assertEquals("team", ChannelNameForm.filterRoot("te am!"))
    }

    @Test
    fun nameFilterKeepsDots() {
        assertEquals("team.ops-1", ChannelNameForm.filterName("Team.Ops-1 "))
    }

    @Test
    fun publicRootIsRefusedInPrivateMode() {
        assertEquals(RootProblem.PublicReserved, ChannelNameForm.privateRootProblem("public"))
        assertNull(ChannelNameForm.fullChannelName(isPrivate = true, root = "public", name = "news"))
    }

    @Test
    fun emptyRootDisablesStart() {
        assertEquals(RootProblem.Empty, ChannelNameForm.privateRootProblem(""))
        assertNull(ChannelNameForm.fullChannelName(isPrivate = true, root = "", name = "news"))
    }

    @Test
    fun typedRootIsUsed() {
        assertNull(ChannelNameForm.privateRootProblem("myteam"))
        assertEquals("myteam.news", ChannelNameForm.fullChannelName(true, "myteam", "news"))
    }

    @Test
    fun publicModeAlwaysUsesPublicRoot() {
        assertEquals("public.news", ChannelNameForm.fullChannelName(false, "", "news"))
        assertEquals("public.news", ChannelNameForm.fullChannelName(false, "public", "news"))
    }

    @Test
    fun emptyNameDisablesStart() {
        assertNull(ChannelNameForm.fullChannelName(false, root, ""))
        assertNull(ChannelNameForm.fullChannelName(true, root, "  "))
    }

    // --- 2. paste / type root.name ---

    @Test
    fun pastingFullNameInPrivateFillsRootAndName() {
        // The case found in testing: the iPad could not join the phone's channel.
        assertEquals(Fields("4cdc4115", "nametest-096499"), privateInput("", "4cdc4115.nametest-096499"))
    }

    @Test
    fun oldEightHexRootJoinsByTyping() {
        var f = Fields(root, "")
        for (c in "4cdc4115.nametest") f = privateInput(f.name, f.name + c, f.root)
        assertEquals(Fields("4cdc4115", "nametest"), f)
        assertEquals("4cdc4115.nametest", ChannelNameForm.fullChannelName(true, f.root, f.name))
    }

    @Test
    fun pastedFullNameIsFilteredAndSplitAtFirstDot() {
        assertEquals(Fields("abc", "team.ops"), privateInput("", "ABC.Team.Ops"))
    }

    @Test
    fun namePastedAfterSplitKeepsItsDotsWhileEdited() {
        val pasted = privateInput("", "abc.team.ops")
        val typed = privateInput(pasted.name, pasted.name + "x", pasted.root)
        assertEquals(Fields("abc", "team.opsx"), typed)
        val addedSegment = privateInput(typed.name, typed.name + ".", typed.root)
        assertEquals(Fields("abc", "team.opsx."), addedSegment)
    }

    @Test
    fun pastingOverADottedNameReplacesTheRoot() {
        assertEquals(Fields("xyz", "foo"), privateInput("team.ops", "xyz.foo", "abc"))
    }

    private fun selectAllPaste(previous: String, pasted: String, r: String) =
        ChannelNameForm.onNameInput(previous, pasted, isPrivate = true, root = r,
            selectionStart = 0, selectionEnd = previous.length)

    @Test
    fun selectAllPasteSharingOnlyTheFirstCharacterReplacesTheRoot() {
        // "tim.chat" over "team.ops" shares the leading "t"; the old text-only
        // inference kept root "abc" and joined "abc.tim.chat".
        assertEquals(Fields("tim", "chat"), selectAllPaste("team.ops", "tim.chat", "abc"))
        assertEquals(Fields("d1e2f3a4b5c6d7e8", "x"), selectAllPaste("deploy.x", "d1e2f3a4b5c6d7e8.x", "abc"))
    }

    @Test
    fun selectAllPasteSharingTheOldStartOrEndReplacesTheRoot() {
        assertEquals(Fields("team", "bar"), selectAllPaste("team.ops", "team.bar", "abc"))
        assertEquals(Fields("x", "ops"), selectAllPaste("team.ops", "x.ops", "abc"))
    }

    @Test
    fun typingADotInsideADottedNameAtTheCursorDoesNotSplit() {
        val f = ChannelNameForm.onNameInput("team.ops", "t.eam.ops", isPrivate = true, root = "abc",
            selectionStart = 1, selectionEnd = 1)
        assertEquals(Fields("abc", "t.eam.ops"), f)
    }

    @Test
    fun leadingDotNeverWipesTheRoot() {
        assertEquals(Fields("myteam", ".ops"), privateInput("ops", ".ops", "myteam"))
        assertEquals(Fields("myteam", "."), privateInput("", ".", "myteam"))
        assertEquals(Fields("myteam", ".x"), selectAllPaste("team.ops", ".x", "myteam"))
    }

    @Test
    fun screenPassesTheNameSelectionSoPastesAreSeen() {
        val screen = File("src/main/kotlin/com/newendian/retichat/ui/channels/JoinChannelScreen.kt").readText()
        assertTrue(screen.contains("selectionStart = nameField.selection.min"))
        assertTrue(screen.contains("selectionEnd = nameField.selection.max"))
    }

    @Test
    fun privateNameWithoutDotLeavesRootAlone() {
        assertEquals(Fields(root, "general"), privateInput("genera", "general"))
    }

    @Test
    fun pastingPublicNameInPrivateGivesRefusedRoot() {
        val f = privateInput("", "public.news")
        assertEquals(Fields("public", "news"), f)
        assertEquals(RootProblem.PublicReserved, ChannelNameForm.privateRootProblem(f.root))
    }

    @Test
    fun publicModeDropsDuplicatePublicPrefix() {
        assertEquals(Fields(root, "news"), publicInput("", "public.news"))
        assertEquals(Fields(root, "news.tech"), publicInput("", "Public.News.Tech"))
    }

    @Test
    fun publicModeKeepsOtherDottedNamesAsTyped() {
        assertEquals(Fields(root, "abc.news"), publicInput("", "abc.news"))
        assertEquals(Fields(root, "team."), publicInput("team", "team."))
    }

    // --- 3. default root: 16 hex from a CSPRNG ---

    @Test
    fun randomRootIsSixteenLowercaseHex() {
        repeat(50) {
            val r = ChannelNameForm.randomPrivateRoot()
            assertEquals(16, r.length)
            assertTrue(r, r.all { it in '0'..'9' || it in 'a'..'f' })
            assertNull(ChannelNameForm.privateRootProblem(r))
        }
    }

    @Test
    fun randomRootsDiffer() {
        assertNotEquals(ChannelNameForm.randomPrivateRoot(), ChannelNameForm.randomPrivateRoot())
    }

    @Test
    fun randomRootUsesAllEightBytesOfTheSecureRandom() {
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) bytes[i] = (0xF0 + i).toByte()
            }
        }
        assertEquals("f0f1f2f3f4f5f6f7", ChannelNameForm.randomPrivateRoot(fixed))
    }

    @Test
    fun channelFormSourcesNeverUseInsecureRandom() {
        val dir = File("src/main/kotlin/com/newendian/retichat/ui/channels")
        val sources = listOf("ChannelNameForm.kt", "JoinChannelScreen.kt").map { File(dir, it).readText() }
        for (src in sources) {
            assertTrue(!src.contains("kotlin.random") && !src.contains("Random.next") && !src.contains("Math.random"))
        }
        assertTrue(sources[1].contains("ChannelNameForm.randomPrivateRoot()"))
        assertTrue(!sources[1].contains("randomHex8"))
    }

    // --- 4. hint text ---

    @Test
    fun privateHintSaysFullName() {
        val screen = File("src/main/kotlin/com/newendian/retichat/ui/channels/JoinChannelScreen.kt").readText()
        assertTrue(screen.contains("Only people you share the full name with can join."))
        assertTrue(!screen.contains("Private channel prefix:"))
    }
}
