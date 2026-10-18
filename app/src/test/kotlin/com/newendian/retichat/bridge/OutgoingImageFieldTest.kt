package com.newendian.retichat.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A photo leaves as FIELD_IMAGE (0x06, `[type, data]`), which MeshChat and
 * Sideband show inline; as FIELD_FILE_ATTACHMENTS (0x05) it was a download
 * there (James, 2026-10-17; Retichat-ios #8). The field holds one picture: the
 * last one of a type they show (jpg, jpeg, png, gif, webp) goes there and the
 * rest stay 0x05.
 */
class OutgoingImageFieldTest {
    private fun list(vararg names: String) = names.map { it to byteArrayOf(1) }

    @Test
    fun aPhotoIsTheImageWithItsExtensionAsTheType() {
        assertEquals(0 to "jpg", LxmfFields.outgoingImage(list("photo_1.jpg")))
        assertEquals(0 to "jpg", LxmfFields.outgoingImage(list("IMG_0001.JPG")))
        assertEquals(0 to "jpeg", LxmfFields.outgoingImage(list("a.jpeg")))
        assertEquals(0 to "png", LxmfFields.outgoingImage(list("a.png")))
        assertEquals(0 to "gif", LxmfFields.outgoingImage(list("a.gif")))
        assertEquals(0 to "webp", LxmfFields.outgoingImage(list("a.webp")))
    }

    @Test
    fun anythingElseStaysAFile() {
        assertNull(LxmfFields.outgoingImage(emptyList()))
        // heic: neither a browser's <img> nor Sideband shows it.
        assertNull(LxmfFields.outgoingImage(list("a.heic", "b.pdf", "jpg", "c.jpg.zip")))
    }

    @Test
    fun theLastPictureIsTheImageSoReceiversKeepTheOrder() {
        // Retichat and the web list 0x05 and then 0x06: [a, b] as 0x05 [a] + 0x06 b reads a, b.
        assertEquals(1 to "png", LxmfFields.outgoingImage(list("a.jpg", "b.png", "c.pdf")))
        assertEquals(2 to "webp", LxmfFields.outgoingImage(list("a.jpg", "b.jpg", "c.webp")))
    }

    @Test
    fun whatIsSentReadsBackAsAPicture() {
        // {0x06: ["jpg", bin 01 02 03]}, as message_set_image writes it (LXMF-rust ffi.rs test).
        val f = LxmfFields.decode(byteArrayOf(0x81.toByte(), 0x06, 0x92.toByte(), 0xa3.toByte(), 'j'.code.toByte(),
            'p'.code.toByte(), 'g'.code.toByte(), 0xc4.toByte(), 3, 1, 2, 3))
        assertEquals(listOf("image.jpg"), f.getAttachments().map { it.first })
    }

    @Test
    fun everySendWritesAttachmentsOneWay() {
        val bridge = File("src/main/kotlin/com/newendian/retichat/bridge/RetichatBridge.kt").readText()
        val add = bridge.substring(bridge.indexOf("fun messageAddAttachments("))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(add.contains("val image = LxmfFields.outgoingImage(attachments)"))
        assertTrue("a picture the field refuses still goes, as a file",
            add.contains("if (image != null && i == image.first && messageSetImage(handle, image.second, data)) return@forEachIndexed\n            messageAddAttachment(handle, name, data)"))
        assertTrue(bridge.contains("private external fun nativeMessageSetImage(handle: Long, imageType: String, data: ByteArray): Int"))

        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        assertEquals("the distro, direct and Group sends", 3, Regex("RetichatBridge\\.messageAddAttachments\\(").findAll(repo).count())

        val jni = File("../rust/retichat-jni/src/lib.rs").readText()
        assertTrue(jni.contains("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageSetImage("))
        assertTrue(jni.contains("ok_or_neg(lxmf::message_set_image(handle as u64, &t, &d))"))
    }

    @Test
    fun aHeicIsShownInlineToo() {
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        assertTrue(repo.contains("\"heic\" -> \"image/heic\""))
    }
}
