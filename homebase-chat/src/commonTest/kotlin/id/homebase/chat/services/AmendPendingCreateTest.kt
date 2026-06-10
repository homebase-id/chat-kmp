package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.uuid.Uuid

/**
 * [amendPendingCreate] is what keeps an edit-while-pending from dropping media.
 * A create is a full snapshot, so when we fold an edit into a still-queued
 * create we must keep the original media payloads and swap only the header +
 * the text-overflow payload (DefaultPayloadKey).
 */
class AmendPendingCreateTest {

    private val mediaKey = "chat_web0"
    private val overflowKey = ChatProtocol.DefaultPayloadKey

    private fun payload(key: String) = PayloadFile(key = key, filePath = "/tmp/$key.enc")

    private fun meta(content: String) = UploadFileMetadata(
        allowDistribution = true,
        isEncrypted = true,
        appData = UploadAppFileMetaData(uniqueId = Uuid.random(), content = content),
    )

    private fun create(payloads: List<PayloadFile>): UploadFileRequest {
        val key = KeyHeader.newRandom16()
        return UploadFileRequest(
            driveId = Uuid.random(),
            keyHeader = key,
            metadata = meta("old header"),
            payloads = payloads,
            thumbnails = emptyList(),
            transitOptions = null,
        )
    }

    @Test
    fun keepsMedia_replacesOverflow() {
        val media = payload(mediaKey)
        val existing = create(listOf(media, payload(overflowKey)))   // media + old overflow
        val newMeta = meta("new header")
        val newOverflow = payload(overflowKey)

        val result = amendPendingCreate(existing, newMeta, listOf(newOverflow))

        // media survives; the old overflow is dropped; the new overflow is appended.
        assertEquals(listOf(media, newOverflow), result.payloads)
        // header swapped to the edited content.
        assertSame(newMeta, result.metadata)
    }

    @Test
    fun shortEdit_dropsOverflow_keepsMedia() {
        val media = payload(mediaKey)
        val existing = create(listOf(media, payload(overflowKey)))

        // Edited text now fits in the header → no overflow payload.
        val result = amendPendingCreate(existing, meta("short"), emptyList())

        assertEquals(listOf(media), result.payloads)
    }

    @Test
    fun textOnly_swapsOverflowOnly() {
        val existing = create(listOf(payload(overflowKey)))           // no media
        val newOverflow = payload(overflowKey)

        val result = amendPendingCreate(existing, meta("new"), listOf(newOverflow))

        assertEquals(listOf(newOverflow), result.payloads)
    }

    @Test
    fun preservesEnvelope() {
        val existing = create(listOf(payload(mediaKey)))

        val result = amendPendingCreate(existing, meta("new"), emptyList())

        // keyHeader, thumbnails, driveId, transitOptions are carried through untouched.
        assertSame(existing.keyHeader, result.keyHeader)
        assertEquals(existing.driveId, result.driveId)
        assertEquals(existing.thumbnails, result.thumbnails)
        assertEquals(existing.transitOptions, result.transitOptions)
    }

    @Test
    fun multipleMediaPreserved_inOrder() {
        val m0 = payload("chat_web0")
        val m1 = payload("chat_web1")
        val existing = create(listOf(m0, m1, payload(overflowKey)))
        val newOverflow = payload(overflowKey)

        val result = amendPendingCreate(existing, meta("new"), listOf(newOverflow))

        assertEquals(listOf(m0, m1, newOverflow), result.payloads)
    }
}
