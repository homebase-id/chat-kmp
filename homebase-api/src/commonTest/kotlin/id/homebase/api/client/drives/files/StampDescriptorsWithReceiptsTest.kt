package id.homebase.api.client.drives.files

import id.homebase.api.client.drives.upload.CreateFileResult
import id.homebase.api.client.drives.upload.PayloadUploadReceipt
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [stampDescriptorsWithReceipts] — preparing local descriptors for the
 * upload-time cache re-key — plus the wire contract for the additive
 * `payloads` receipts field on upload results.
 */
class StampDescriptorsWithReceiptsTest {

    private val thumbs = listOf(ThumbnailDescriptor(pixelWidth = 100, pixelHeight = 100, contentType = "image/webp"))

    @Test
    fun matchingReceiptStampsUidAndLastModifiedAndKeepsThumbnails() {
        val stamped = stampDescriptorsWithReceipts(
            localPayloads = listOf(PayloadDescriptor(key = "img_key1", thumbnails = thumbs)),
            receipts = listOf(PayloadUploadReceipt(key = "img_key1", uid = 112233445566778899L, lastModified = 1749746629123L)),
        )

        val d = stamped.single()
        assertEquals(112233445566778899L, d.uid)
        assertEquals(1749746629123L, d.lastModified)
        assertEquals(thumbs, d.thumbnails, "thumbnail dims must survive — they drive which thumb entries move")
    }

    @Test
    fun receiptKeyMatchingIsCaseInsensitive() {
        val stamped = stampDescriptorsWithReceipts(
            localPayloads = listOf(PayloadDescriptor(key = "IMG_KEY1", thumbnails = thumbs)),
            receipts = listOf(PayloadUploadReceipt(key = "img_key1", uid = 1L, lastModified = 2L)),
        )
        assertEquals(2L, stamped.single().lastModified)
    }

    @Test
    fun missingReceiptStripsThumbnailsButKeepsThePayloadMovable() {
        // Old server: no receipts. The payload entry's cache key needs no
        // lastModified, so it stays movable; the thumb target keys can't be
        // computed, so the thumbnails are dropped from the re-key.
        val stamped = stampDescriptorsWithReceipts(
            localPayloads = listOf(PayloadDescriptor(key = "img_key1", thumbnails = thumbs)),
            receipts = emptyList(),
        )

        val d = stamped.single()
        assertEquals("img_key1", d.key)
        assertNull(d.thumbnails)
        assertNull(d.lastModified)
    }

    @Test
    fun mixedReceiptsStampOnlyTheMatchingKeys() {
        val stamped = stampDescriptorsWithReceipts(
            localPayloads = listOf(
                PayloadDescriptor(key = "img_key1", thumbnails = thumbs),
                PayloadDescriptor(key = "dflt_key"),
            ),
            receipts = listOf(PayloadUploadReceipt(key = "img_key1", uid = 1L, lastModified = 2L)),
        )

        assertEquals(2L, stamped[0].lastModified)
        assertEquals(thumbs, stamped[0].thumbnails)
        assertNull(stamped[1].lastModified)
        assertNull(stamped[1].thumbnails)
    }

    // ---- wire contract for the additive receipts field ----

    @Test
    fun createFileResultParsesReceiptsFromTheV2Response() {
        val json = """
            {
              "fileId": "11111111-1111-1111-1111-111111111111",
              "driveId": "22222222-2222-2222-2222-222222222222",
              "recipientStatus": {},
              "newVersionTag": "33333333-3333-3333-3333-333333333333",
              "payloads": [
                { "key": "mypayload1", "uid": 112233445566778899, "lastModified": 1749746629123 }
              ]
            }
        """.trimIndent()

        val result = OdinSystemSerializer.deserialize<CreateFileResult>(json)

        val receipt = result.payloads.single()
        assertEquals("mypayload1", receipt.key)
        assertEquals(112233445566778899L, receipt.uid)
        assertEquals(1749746629123L, receipt.lastModified)
    }

    @Test
    fun createFileResultWithoutReceiptsFieldParsesToEmptyList() {
        // Pre-receipts server response — the field must default, not throw.
        val json = """
            {
              "fileId": "11111111-1111-1111-1111-111111111111",
              "driveId": "22222222-2222-2222-2222-222222222222",
              "newVersionTag": "33333333-3333-3333-3333-333333333333"
            }
        """.trimIndent()

        val result = OdinSystemSerializer.deserialize<CreateFileResult>(json)

        assertTrue(result.payloads.isEmpty())
    }
}
