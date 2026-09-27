package com.newendian.retichat.names

import com.newendian.retichat.bridge.ChannelLxmUnpackResult
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** LXMF-rust/DISPLAY_NAMES.md: the app-side rules (§4.2, §5.2, §5.3). */
class DisplayNamesTest {
    private val hash = "0123456789abcdef0123456789abcdef"

    // ── §5.3 resolver ────────────────────────────────────────────────

    @Test
    fun shortHashIsEightHexAndAnEllipsis() {
        assertEquals("01234567…", DisplayNames.shortHash(hash))
        assertEquals("01234567…", DisplayNames.shortHash(hash.uppercase()))
    }

    @Test
    fun contactResolvesLocalThenMessageThenAnnounceThenHash() {
        val all = ContactNames(localName = "Mum", messageName = "Alice", announceName = "alice@home")
        assertEquals("Mum", DisplayNames.contact(all, hash))
        assertEquals("Alice", DisplayNames.contact(all.copy(localName = null), hash))
        assertEquals("alice@home", DisplayNames.contact(all.copy(localName = null, messageName = null), hash))
        assertEquals("01234567…", DisplayNames.contact(ContactNames(), hash))
        assertEquals("01234567…", DisplayNames.contact(null, hash))
        // An empty slot is no name, never a blank label.
        assertEquals("Alice", DisplayNames.contact(ContactNames(localName = "", messageName = "Alice"), hash))
    }

    @Test
    fun channelPostPrefersTheChannelNameAndShowsTheHashBesideIt() {
        val names = ContactNames(localName = "Mum", messageName = "Alice")
        assertEquals(ChannelLabel("Night Owl", "01234567…"), DisplayNames.channelPost("Night Owl", names, hash))
        // No channel name: the contact's name, with no hash beside it.
        assertEquals(ChannelLabel("Mum", null), DisplayNames.channelPost(null, names, hash))
        assertEquals(ChannelLabel("01234567…", null), DisplayNames.channelPost(null, null, hash))
    }

    @Test
    fun nameBookIsCaseInsensitiveAndSelfReadsYouInMemberLists() {
        val self = "f".repeat(32)
        val book = NameBook(mapOf(hash to ContactNames(messageName = "Alice")), self)
        assertEquals("Alice", book.contact(hash.uppercase()))
        assertEquals("Alice", book.member(hash))
        assertEquals("You", book.member(self))
        assertEquals("ffffffff…", book.contact(self))
        assertEquals(ChannelLabel("Owl", "01234567…"), book.channelPost(hash, "Owl"))
    }

    @Test
    fun systemLinesResolveTheirMemberWhenShown() {
        val before = NameBook()
        val after = NameBook(mapOf(hash to ContactNames(messageName = "Alice")))
        assertEquals("01234567… joined the group", SystemText.render(SystemText.MEMBER, "joined the group", hash, before))
        assertEquals("Alice joined the group", SystemText.render(SystemText.MEMBER, "joined the group", hash, after))
        assertEquals("plain text", SystemText.render(null, "plain text", hash, after))
    }

    // ── §5.2 accepting a 0xD1 ─────────────────────────────────────────

    @Test
    fun validatedNamesSetAndClear() {
        val ok = Signature.VALIDATED
        assertEquals(NameUpdate.Set("Alice"), DisplayNames.acceptMessageName(null, NameField.Name("Alice"), ok))
        assertEquals(NameUpdate.Set("Alicia"), DisplayNames.acceptMessageName("Alice", NameField.Name("Alicia"), ok))
        assertEquals(NameUpdate.Unchanged, DisplayNames.acceptMessageName("Alice", NameField.Name("Alice"), ok))
        assertEquals(NameUpdate.Set(null), DisplayNames.acceptMessageName("Alice", NameField.Clear, ok))
        assertEquals(NameUpdate.Unchanged, DisplayNames.acceptMessageName("Alice", NameField.Absent, ok))
    }

    @Test
    fun sourceUnknownOnlyFillsAnEmptySlotAndNeverClears() {
        val unknown = Signature.SOURCE_UNKNOWN
        assertEquals(NameUpdate.Set("Alice"), DisplayNames.acceptMessageName(null, NameField.Name("Alice"), unknown))
        assertEquals(NameUpdate.Unchanged, DisplayNames.acceptMessageName("Alice", NameField.Name("Mallory"), unknown))
        assertEquals(NameUpdate.Unchanged, DisplayNames.acceptMessageName("Alice", NameField.Clear, unknown))
    }

    @Test
    fun invalidSignaturesNeverTouchTheName() {
        for (reason in listOf(Signature.INVALID, 7, -1)) {
            assertEquals(NameUpdate.Unchanged, DisplayNames.acceptMessageName(null, NameField.Name("Mallory"), reason))
            assertEquals(NameUpdate.Unchanged, DisplayNames.acceptMessageName("Alice", NameField.Name("Mallory"), reason))
            assertEquals(NameUpdate.Unchanged, DisplayNames.acceptMessageName("Alice", NameField.Clear, reason))
        }
    }

    @Test
    fun distroUnwrapFieldsMapToTheSameStates() {
        assertEquals(NameField.Absent, NameField.fromState(0, null))
        assertEquals(NameField.Clear, NameField.fromState(1, null))
        assertEquals(NameField.Name("Alice"), NameField.fromState(2, "Alice"))
        assertEquals(NameField.Absent, NameField.fromState(2, null))
        assertEquals(NameField.Absent, NameField.fromState(9, "Alice"))
        assertEquals(Signature.VALIDATED, Signature.reason(true, null))
        assertEquals(Signature.SOURCE_UNKNOWN, Signature.reason(false, 1))
        assertEquals(Signature.INVALID, Signature.reason(false, 2))
        // Not validated and no reason: never lets a name through.
        assertEquals(Signature.INVALID, Signature.reason(false, null))
    }

    @Test
    fun channelNamesSetAndClearPerPost() {
        assertEquals(NameUpdate.Set("Owl"), DisplayNames.acceptChannelName(null, 0, NameField.Name("Owl"), 10))
        assertEquals(NameUpdate.Set(null), DisplayNames.acceptChannelName("Owl", 10, NameField.Clear, 20))
        assertEquals(NameUpdate.Unchanged, DisplayNames.acceptChannelName("Owl", 10, NameField.Absent, 20))
        // A newer post repeating the name is recorded (its timestamp matters).
        assertEquals(NameUpdate.Set("Owl"), DisplayNames.acceptChannelName("Owl", 10, NameField.Name("Owl"), 20))
        assertEquals(NameUpdate.Unchanged, DisplayNames.acceptChannelName("Owl", 20, NameField.Name("Owl"), 20))
        assertEquals(NameUpdate.Set(null), DisplayNames.acceptChannelName(null, 10, NameField.Clear, 20))
    }

    /** What RfedChannelClient.recordChannelSender keeps: the name and the timestamp that set it. */
    private data class Held(val name: String?, val at: Long)

    private fun Held.apply(field: NameField, postAt: Long): Held =
        when (val u = DisplayNames.acceptChannelName(name, at, field, postAt)) {
            NameUpdate.Unchanged -> this
            is NameUpdate.Set -> Held(u.name, postAt)
        }

    @Test
    fun aNewerPostRepeatingTheNameProtectsItFromAnOlderClearPulledLate() {
        // "A" at 10, a clear at 12, "A" again at 20. Live: 10 and 20; the
        // history pull then brings 12.
        val held = Held(null, 0)
            .apply(NameField.Name("A"), 10)
            .apply(NameField.Name("A"), 20)
            .apply(NameField.Clear, 12)
        assertEquals(Held("A", 20), held)
        // The same with a clear repeated: an older name pulled late loses.
        val cleared = Held(null, 0)
            .apply(NameField.Clear, 10)
            .apply(NameField.Clear, 20)
            .apply(NameField.Name("B"), 15)
        assertEquals(Held(null, 20), cleared)
    }

    @Test
    fun anOlderChannelPostPulledLateDoesNotUndoANewerName() {
        assertEquals(NameUpdate.Unchanged, DisplayNames.acceptChannelName("Lark", 20, NameField.Name("Owl"), 10))
        assertEquals(NameUpdate.Unchanged, DisplayNames.acceptChannelName("Lark", 20, NameField.Clear, 10))
        assertEquals(NameUpdate.Set("Owl"), DisplayNames.acceptChannelName("Lark", 20, NameField.Name("Owl"), 20))
    }

    @Test
    fun announcesReplaceTheAnnounceNameAndANamelessOneClearsIt() {
        assertEquals(NameUpdate.Set("Alice"), DisplayNames.acceptAnnounceName(null, "Alice"))
        assertEquals(NameUpdate.Set(null), DisplayNames.acceptAnnounceName("Alice", null))
        assertEquals(NameUpdate.Set(null), DisplayNames.acceptAnnounceName("Alice", ""))
        assertEquals(NameUpdate.Unchanged, DisplayNames.acceptAnnounceName("Alice", "Alice"))
    }

    // ── Bridge trailers ───────────────────────────────────────────────

    private fun trailer(state: Int, name: String): ByteArray {
        val b = name.toByteArray(Charsets.UTF_8)
        return byteArrayOf(state.toByte(), (b.size shr 8).toByte(), b.size.toByte()) + b
    }

    @Test
    fun decodeTrailerStates() {
        assertEquals(NameField.Absent, NameField.fromTrailer(trailer(0, "")))
        assertEquals(NameField.Clear, NameField.fromTrailer(trailer(1, "")))
        assertEquals(NameField.Name("Алиса 👋"), NameField.fromTrailer(trailer(2, "Алиса 👋")))
        assertEquals(NameField.Absent, NameField.fromTrailer(null))
        assertEquals(NameField.Absent, NameField.fromTrailer(byteArrayOf(2, 0)))
        // Length runs past the end, unknown state, empty name: absent.
        assertEquals(NameField.Absent, NameField.fromTrailer(byteArrayOf(2, 0, 9, 65)))
        assertEquals(NameField.Absent, NameField.fromTrailer(trailer(3, "x")))
        assertEquals(NameField.Absent, NameField.fromTrailer(trailer(2, "")))
    }

    private fun unpacked(sigOk: Int, reason: Int, title: String, content: String, tail: ByteArray): ByteArray {
        val t = title.toByteArray()
        val c = content.toByteArray()
        val head = ByteArray(32)
        head[24] = sigOk.toByte()
        head[25] = reason.toByte()
        head[26] = (t.size shr 8).toByte(); head[27] = t.size.toByte()
        head[28] = (c.size shr 24).toByte(); head[29] = (c.size shr 16).toByte()
        head[30] = (c.size shr 8).toByte(); head[31] = c.size.toByte()
        return head + t + c + tail
    }

    @Test
    fun channelUnpackReadsTheNameTrailerAfterTheContent() {
        val post = ChannelLxmUnpackResult.parse(unpacked(1, 0, "t", "hello", trailer(2, "Owl")))!!
        assertEquals("hello", String(post.content))
        assertEquals(NameField.Name("Owl"), post.displayName)
        assertEquals(NameField.Clear, ChannelLxmUnpackResult.parse(unpacked(1, 0, "", "x", trailer(1, "")))!!.displayName)
        // No trailer (an older decoder's buffer): absent.
        assertEquals(NameField.Absent, ChannelLxmUnpackResult.parse(unpacked(1, 0, "", "x", ByteArray(0)))!!.displayName)
        // A post that did not validate never yields a name.
        assertEquals(NameField.Absent, ChannelLxmUnpackResult.parse(unpacked(0, 1, "", "x", trailer(2, "Owl")))!!.displayName)
    }

    // ── §4.2 channel send rule ────────────────────────────────────────

    private val now = 1_000_000_000_000L
    private val day = DisplayNames.CHANNEL_NAME_REFRESH_MS

    @Test
    fun firstPostAndANameChangeCarryTheName() {
        assertEquals(NameField.Name("Owl"), DisplayNames.channelPostName("Owl", null, null, false, now))
        val owl = DisplayNames.digestHex("Owl")
        assertEquals(NameField.Name("Lark"), DisplayNames.channelPostName("Lark", owl, now - 1, false, now))
    }

    @Test
    fun anUnchangedNameIsLeftOutUntilANewSenderOrADayPasses() {
        val owl = DisplayNames.digestHex("Owl")
        assertEquals(NameField.Absent, DisplayNames.channelPostName("Owl", owl, now - 1000, false, now))
        assertEquals(NameField.Name("Owl"), DisplayNames.channelPostName("Owl", owl, now - 1000, true, now))
        assertEquals(NameField.Absent, DisplayNames.channelPostName("Owl", owl, now - day, false, now))
        assertEquals(NameField.Name("Owl"), DisplayNames.channelPostName("Owl", owl, now - day - 1, false, now))
    }

    @Test
    fun unsettingTheNameClearsOnceThenSendsNothing() {
        val owl = DisplayNames.digestHex("Owl")
        assertEquals(NameField.Clear, DisplayNames.channelPostName(null, owl, now - 1000, false, now))
        assertEquals(NameField.Clear, DisplayNames.channelPostName("", owl, now - 1000, true, now))
        assertEquals(NameField.Absent, DisplayNames.channelPostName(null, DisplayNames.EMPTY_DIGEST_HEX, now, true, now))
        assertEquals(NameField.Absent, DisplayNames.channelPostName(null, null, null, true, now))
    }

    /** The digest is the one in the shared vectors LXMF-rust's tests run too (§4.1). */
    @Test
    fun digestMatchesTheSharedVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb924", DisplayNames.EMPTY_DIGEST_HEX)
        assertEquals("3bc51062973c458d5a6f2d8d64a02324", DisplayNames.digestHex("Alice"))
        // The workspace's copy of the vectors, when this repo sits in it.
        val file = File("../../LXMF-rust/tests/display_name_vectors.json")
        Assume.assumeTrue("shared vectors not found at ${file.absolutePath}", file.exists())
        val json = file.readText()
        val section = json.substring(json.indexOf("\"digest\""))
        val entry = Regex("\"input\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"digest_hex\":\\s*\"([0-9a-f]{32})\"")
        val found = entry.findAll(section).toList()
        assertTrue(found.size >= 4)
        for (m in found) {
            assertEquals(m.groupValues[2], DisplayNames.digestHex(jsonUnescape(m.groupValues[1])))
        }
        assertEquals(DisplayNames.EMPTY_DIGEST_HEX, DisplayNames.digestHex(""))
    }

    private fun jsonUnescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'u' -> { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    else -> out.append(e)
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }
}
