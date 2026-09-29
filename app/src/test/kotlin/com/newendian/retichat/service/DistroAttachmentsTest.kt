package com.newendian.retichat.service

import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.bridge.MiniJson
import com.newendian.retichat.service.DistroCodec.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 2026-09-29 bug: a photo sent from the Pixel to a distro address arrived
 * as its caption only. lxmf_rust's distro unwrap now hands on the message's
 * LXMF fields map (DistroMessage::to_json key "fields": the map's msgpack as
 * the sender packed it, standard padded base64, or null), and this app must
 * decode it as it decodes a direct message's fields, so the attachment is
 * saved and shown like any other.
 *
 * The JSON below is real `unwrap_blob(...).to_json()` output: LXMF-rust
 * 06c40e1 unwrapping blobs a sender built for a distro identity, with 0x05
 * [["pixel-photo-1.jpg", photo]], 0x06 ["jpg", photo] and a 0x0C ticket.
 * org.json is only a stub in JVM tests, so the object is read with MiniJson,
 * whose values are what RfedDistroClient reads with `o.opt("fields")` (a
 * JSON null is Kotlin null here and JSONObject.NULL there; both are "no map").
 */
class DistroAttachmentsTest {
    private val photo = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x00, 0x10,
        'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
        0xfb.toByte(), 0xfc.toByte(), 0xfd.toByte(), 0xfe.toByte(), 0xff.toByte(), 0x3e, 0x3f,
    )

    private val captioned = """{"source_hash":"a47157bd61e038c5a7d32104377877c5","timestamp":1790000000.5,"title":"","content":"pixel-photo-1","is_delivery_notification":false,"ticket":"[1790600000, [9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9]]","distro_transfer_key":null,"sent_to":null,"sent_by":null,"display_name_state":0,"display_name":null,"signature_validated":false,"unverified_reason":1,"fields":"gwWRkrFwaXhlbC1waG90by0xLmpwZ8QS/9j/4AAQSkZJRgD7/P3+/z4/BpKjanBnxBL/2P/gABBKRklGAPv8/f7/Pj8MkstB2q6Y0AAAAMQQCQkJCQkJCQkJCQkJCQkJCQ=="}"""
    private val captionlessWithTicket = """{"source_hash":"a47157bd61e038c5a7d32104377877c5","timestamp":1790000000.5,"title":"","content":"","is_delivery_notification":false,"ticket":"[1790600000, [9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9]]","distro_transfer_key":null,"sent_to":null,"sent_by":null,"display_name_state":0,"display_name":null,"signature_validated":false,"unverified_reason":1,"fields":"gwWRkrFwaXhlbC1waG90by0xLmpwZ8QS/9j/4AAQSkZJRgD7/P3+/z4/BpKjanBnxBL/2P/gABBKRklGAPv8/f7/Pj8MkstB2q6Y0AAAAMQQCQkJCQkJCQkJCQkJCQkJCQ=="}"""
    private val emptyMap = """{"source_hash":"a47157bd61e038c5a7d32104377877c5","timestamp":1790000000.5,"title":"","content":"hi","is_delivery_notification":false,"ticket":null,"distro_transfer_key":null,"sent_to":null,"sent_by":null,"display_name_state":0,"display_name":null,"signature_validated":false,"unverified_reason":1,"fields":"gA=="}"""
    private val noMap = """{"source_hash":"a47157bd61e038c5a7d32104377877c5","timestamp":1790000000.5,"title":"","content":"no map","is_delivery_notification":false,"ticket":null,"distro_transfer_key":null,"sent_to":null,"sent_by":null,"display_name_state":0,"display_name":null,"signature_validated":false,"unverified_reason":1,"fields":null}"""

    @Suppress("UNCHECKED_CAST")
    private fun obj(json: String) = MiniJson.parse(json) as Map<String, Any?>

    /** RfedDistroClient.handleBlob's read: `o.opt("fields")` → fieldsBytes → LxmfFields.decode. */
    private fun fieldsOf(json: String): LxmfFields = LxmfFields.decode(DistroCodec.fieldsBytes(obj(json)["fields"]))

    @Test
    fun aPhotoSentToTheDistroKeepsItsAttachment() {
        val fields = fieldsOf(captioned)
        val attachments = fields.getFileAttachments()
        assertEquals(1, attachments.size)
        assertEquals("pixel-photo-1.jpg", attachments[0].first)
        assertArrayEquals(photo, attachments[0].second)
        // The whole map, not just 0x05: every field the sender sent.
        assertEquals(3, fields.size)
        assertTrue(fields.has(LxmfFields.FIELD_IMAGE))
        assertTrue(fields.has(LxmfFields.FIELD_TICKET))
    }

    @Test
    fun theFieldsAreTheSendersMsgpackByteForByte() {
        val raw = DistroCodec.fieldsBytes(obj(captioned)["fields"])
        assertNotNull(raw)
        // fixmap(3), 0x05: [[str "pixel-photo-1.jpg", bin(18)]] ...
        assertEquals("83059192b1", raw!!.copyOf(5).toHex())
        assertTrue(raw.toHex().contains(photo.toHex()))
    }

    @Test
    fun aCaptionlessPhotoWithATicketIsAMessageWithItsAttachment() {
        val o = obj(captionlessWithTicket)
        // LXMF-rust 06c40e1: an attachment makes it a message, not a delivery notification.
        assertEquals(false, o["is_delivery_notification"])
        assertEquals("", o["content"])
        assertEquals(listOf("pixel-photo-1.jpg"), fieldsOf(captionlessWithTicket).getFileAttachments().map { it.first })
    }

    @Test
    fun anEmptyMapIsAMapWithNoAttachments() {
        assertArrayEquals(byteArrayOf(0x80.toByte()), DistroCodec.fieldsBytes(obj(emptyMap)["fields"]))
        val fields = fieldsOf(emptyMap)
        assertEquals(0, fields.size)
        assertTrue(fields.getFileAttachments().isEmpty())
    }

    @Test
    fun noFieldsMapMeansNoAttachments() {
        // The key is always there; null when the payload had no map.
        assertTrue(obj(noMap).containsKey("fields"))
        assertNull(DistroCodec.fieldsBytes(obj(noMap)["fields"]))
        assertTrue(fieldsOf(noMap).getFileAttachments().isEmpty())
        // An older core without the key.
        assertNull(DistroCodec.fieldsBytes(obj(noMap.replace(""","fields":null""", ""))["fields"]))
        // org.json's JSONObject.NULL (and any other non-string) is no map either.
        assertNull(DistroCodec.fieldsBytes(Any()))
        assertNull(DistroCodec.fieldsBytes(5L))
    }

    @Test
    fun malformedFieldsCostTheAttachmentsNeverTheMessage() {
        val b64 = obj(captioned)["fields"] as String
        val hex = java.util.Base64.getDecoder().decode(b64).toHex()
        for (bad in listOf(
            "not base64!",
            "gA=",                       // bad padding
            "g",                         // a lone sextet
            b64.dropLast(3),             // truncated
            b64.replace('/', '_').replace('+', '-'), // URL-safe alphabet is not the contract
            "null",                      // what optString would have made of a JSON null
            hex,                         // hex, not base64
        )) {
            // Never throws: RfedDistroClient still hands the message on.
            val raw = DistroCodec.fieldsBytes(bad)
            assertTrue("'${bad.take(24)}' must give no attachments", LxmfFields.decode(raw).getFileAttachments().isEmpty())
        }
        // Not base64 at all is null, which the client logs as unreadable.
        assertNull(DistroCodec.fieldsBytes("not base64!"))
        assertNull(DistroCodec.fieldsBytes("gA="))
    }

    // ---- Wiring that needs the native library, Room and a Context to run,
    // ---- checked in the source (as SendOrderingContractTest does).

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    private val client = src("service/RfedDistroClient.kt")
    private val repo = src("data/repository/ChatRepository.kt")

    /** Members start at four spaces: a KDoc, a comment, or a declaration. */
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")

    private fun body(source: String, decl: String): String {
        val start = source.indexOf(decl)
        assertTrue("$decl not found", start >= 0)
        val end = nextMember.find(source, start + decl.length)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun handleBlobReadsTheFieldsAndHandsThemOn() {
        val handle = body(client, "suspend fun handleBlob(")
        assertTrue(handle.contains("""val fieldsValue = o.opt("fields")"""))
        assertTrue(handle.contains("val fieldsRaw = DistroCodec.fieldsBytes(fieldsValue)"))
        // optString would turn a JSON null into the string "null".
        assertFalse(handle.contains("""optString("fields""""))
        // Unreadable fields cost the attachments, not the message: no early return on them.
        assertFalse(Regex("fieldsRaw\\s*(\\?:|!!)").containsMatchIn(handle))
        assertTrue(handle.contains("onDistroMessageReceived(srcHash, title, content, timestamp, fieldsRaw, nameField, unverifiedReason)"))
        // The sent-copy path keeps its rule: text only.
        assertTrue(handle.contains("app.repository.onDistroSentCopy(copy.recipientHex, title, content, timestamp)"))
    }

    @Test
    fun onDistroMessageReceivedDecodesTheFieldsForTheDirectMessagePath() {
        val received = body(repo, "fun onDistroMessageReceived(")
        // Required, no default: a caller can not forget the fields again.
        assertTrue(Regex("timestamp: Double,\\s*fieldsRaw: ByteArray\\?,\\s*nameField").containsMatchIn(received))
        assertTrue(received.contains("val fields = LxmfFields.decode(fieldsRaw)"))
        assertTrue(received.contains("handleDirectMessage(msgId, srcHash, srcHex, content, timestamp, fields)"))
        assertFalse(received.contains("ByteArray(0)"))
        assertFalse(received.contains("LxmfFields.EMPTY"))
        // ... where the attachments are saved, as for any direct message.
        assertTrue(body(repo, "private suspend fun handleDirectMessage(").contains("saveInboundAttachments(msgId, fields)"))
        // The sent copy stays text only.
        assertTrue(repo.contains("fun onDistroSentCopy(recipientHex: String, title: String, content: String, timestamp: Double)"))
    }

    @Test
    fun everyDistroArrivalGoesThroughHandleBlob() {
        // Live: the propagation stream and rfed.delivery; deferred: /rfed/pull.
        assertTrue(src("service/PropagationStream.kt").contains("RfedDistroClient.handleBlob(app, blob)"))
        assertTrue(src("service/RfedChannelClient.kt").contains("RfedDistroClient.handleBlob(appContext, inner)"))
        assertTrue(body(client, "suspend fun pull(").contains("handleBlob(context, blob)"))
        assertTrue(src("service/WakeWorker.kt").contains("RfedDistroClient.pull(applicationContext)"))
        // And handleBlob is the one caller of onDistroMessageReceived.
        val callers = File("src/main/kotlin").walk().filter { it.extension == "kt" }
            .sumOf { Regex("\\.onDistroMessageReceived\\(").findAll(it.readText()).count() }
        assertEquals(1, callers)
    }
}
