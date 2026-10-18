package com.newendian.retichat.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A picture from MeshChat or Sideband arrives (Retichat-ios #8): LXMF's
 * FIELD_IMAGE (0x06, `[image_type, data]`) and FIELD_AUDIO (0x07,
 * `[audio_mode, data]`) are read beside the file attachments (0x05), as the
 * web reads them (Retichat-js lxmf.js attachmentsFromFields). Until
 * 2026-10-17 only 0x05 was read and such a message arrived as its text alone.
 */
class LxmfImageAudioFieldsTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun decode(hexBytes: String): LxmfFields = try {
        LxmfFields.decode(hex(hexBytes))
    } catch (t: Throwable) {
        throw AssertionError("decode(${hexBytes.take(40)}) threw ${t::class.java.name}", t)
    }

    private val data = "c403010203"   // bin 01 02 03

    private fun names(f: LxmfFields) = f.getAttachments().map { it.first }

    @Test
    fun aMeshChatImageIsAnAttachmentNamedForAnInlineType() {
        // {0x06: ["png", bin 01 02 03]}: what MeshChat sends for a picture.
        val f = decode("8106" + "92" + "a3706e67" + data)
        val all = f.getAttachments()
        assertEquals(listOf("image.png"), all.map { it.first })
        assertArrayEquals(byteArrayOf(1, 2, 3), all.single().second)
        assertTrue("0x05 alone has none", f.getFileAttachments().isEmpty())
        // Sideband's "webp", a MIME type, upper case, and the type as bin.
        assertEquals(listOf("image.webp"), names(decode("8106" + "92" + "a477656270" + data)))
        assertEquals(listOf("image.jpeg"), names(decode("8106" + "92" + "aa696d6167652f6a706567" + data)))
        assertEquals(listOf("image.jpg"), names(decode("8106" + "92" + "a34a5047" + data)))
        assertEquals(listOf("image.png"), names(decode("8106" + "92" + "c403706e67" + data)))
        // heic, named as on iOS (shown there inline, here as a file).
        assertEquals(listOf("image.heic"), names(decode("8106" + "92" + "a468656963" + data)))
    }

    @Test
    fun aTypeNotShownInlineIsAFileAndNeverMakesTheName() {
        // "svg+xml", a path, and nil: the picture still arrives, as "image".
        assertEquals(listOf("image"), names(decode("8106" + "92" + "a7737667" + "2b786d6c" + data)))
        assertEquals(listOf("image"), names(decode("8106" + "92" + "a92e2e2f2e2e2f782e6a" + data)))
        assertEquals(listOf("image"), names(decode("8106" + "92" + "c0" + data)))
    }

    @Test
    fun anImageOrAudioNotOfThatShapeIsSkippedAndTheRestIsRead() {
        // {0x06: "x", 0x02: 7}, {0x06: ["png"]}, {0x06: ["png", "no"]}, {0x07: [16]}.
        val notAList = decode("82" + "06a178" + "0207")
        assertTrue(notAList.getAttachments().isEmpty())
        assertEquals(7L, notAList.getInt(0x02))
        assertTrue(decode("8106" + "91" + "a3706e67").getAttachments().isEmpty())
        assertTrue(decode("8106" + "92" + "a3706e67" + "a26e6f").getAttachments().isEmpty())
        assertTrue(decode("8107" + "91" + "10").getAttachments().isEmpty())
    }

    @Test
    fun audioIsOfferedAsAFileNamedForItsMode() {
        assertEquals(listOf("audio.ogg"), names(decode("8107" + "92" + "10" + data)))      // AM_OPUS_OGG
        assertEquals(listOf("audio.ogg"), names(decode("8107" + "92" + "cc11" + data)))    // a uint8 mode
        assertEquals(listOf("audio.c2"), names(decode("8107" + "92" + "05" + data)))       // AM_CODEC2_1300
        assertEquals(listOf("audio.bin"), names(decode("8107" + "92" + "30" + data)))
        assertEquals(listOf("audio.bin"), names(decode("8107" + "92" + "a178" + data)))
    }

    @Test
    fun theFileAttachmentsComeFirstThenTheImageThenTheAudio() {
        // {0x05: [["a.txt", bin 09]], 0x06: ["png", bin 01 02 03], 0x07: [16, bin 0a]}
        val f = decode("83" + "05" + "91" + "92" + "a5612e747874" + "c40109" + "06" + "92" + "a3706e67" + data + "07" + "92" + "10" + "c4010a")
        assertEquals(listOf("a.txt", "image.png", "audio.ogg"), names(f))
        assertEquals(listOf("a.txt"), f.getFileAttachments().map { it.first })
    }

    @Test
    fun everyReceivePathReadsThemAll() {
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        assertFalse("no receive path reads 0x05 alone", repo.contains("getFileAttachments()"))
        val save = repo.substring(repo.indexOf("private suspend fun saveInboundAttachments("))
        assertTrue(save.contains("val attachments = fields.getAttachments()"))
        assertTrue("a Group post with only a picture is a post",
            repo.contains("val attachments = fields.getAttachments()\n                val post = DeliveryPolicy.groupPost(standing.isSelf(srcHex), signature, content.isNotBlank() || attachments.isNotEmpty())"))
    }
}
