package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ResendPayloadByteSource
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.chat.services.convo.FakeFileOperationsProvider
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * [resendablePayloads] — the shared payload-recovery helper used by the
 * conversation heal-redistribute path and chat message retry. Exercises the
 * recovered/unavailable split that retry's recoverability decision hangs on.
 */
@OptIn(ExperimentalEncodingApi::class)
class ResendablePayloadsTest {

    private val driveId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val iv = ByteArray(16) { it.toByte() }
    private val fileOps = FakeFileOperationsProvider()

    /** Byte source whose responses are scripted per payload key; null = gone. */
    private class ScriptedByteSource(
        private val payloadBytes: Map<String, ByteArray?>,
        private val thumbBytes: ByteArray? = ByteArray(256) { 2 },
        private val throwOnKey: String? = null,
    ) : ResendPayloadByteSource {
        val payloadRequests = mutableListOf<String>()

        override suspend fun getPayloadBytesEncrypted(driveId: Uuid, fileId: Uuid, key: String): ByteArray? {
            payloadRequests += key
            if (key == throwOnKey) throw IllegalStateException("boom for $key")
            return payloadBytes[key]
        }

        override suspend fun getThumbBytesEncrypted(
            driveId: Uuid,
            fileId: Uuid,
            payloadKey: String,
            width: Int,
            height: Int,
            lastModified: Long?,
        ): ByteArray? = thumbBytes
    }

    private fun fileWith(vararg descriptors: PayloadDescriptor): HomebaseFile = HomebaseFile(
        fileId = Uuid.random(),
        driveId = driveId,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.newRandom16(),
        fileMetadata = FileMetadata(payloads = descriptors.toList().ifEmpty { null }),
        serverMetadata = ServerMetadata(),
    )

    private fun descriptor(
        key: String,
        withIv: Boolean = true,
        thumbs: List<ThumbnailDescriptor>? = null,
    ) = PayloadDescriptor(
        key = key,
        contentType = "image/jpeg",
        iv = if (withIv) Base64.encode(iv) else null,
        thumbnails = thumbs,
    )

    @Test
    fun `available bytes are recovered as pre-encrypted payloads with iv and thumbnails`() = runTest {
        val bytes = ByteArray(2048) { 1 }
        val source = ScriptedByteSource(mapOf("img_key1" to bytes))
        val file = fileWith(
            descriptor(
                "img_key1",
                thumbs = listOf(ThumbnailDescriptor(pixelWidth = 100, pixelHeight = 100, contentType = "image/webp")),
            ),
        )

        val result = resendablePayloads(file, driveId, source, fileOps)

        assertEquals(1, result.payloads.size)
        val payload = result.payloads.single()
        assertEquals("img_key1", payload.key)
        assertTrue(payload.isPreEncrypted, "recovered bytes are already ciphertext")
        assertContentEquals(iv, payload.iv, "the descriptor's original iv must be carried")
        assertEquals(1, result.thumbnails.size, "the payload's thumbnail must ship too (AppendOrOverwrite wipes it otherwise)")
        assertEquals("img_key1", result.thumbnails.single().key)
        assertTrue(result.unavailableKeys.isEmpty())
    }

    @Test
    fun `missing bytes are reported unavailable not silently dropped`() = runTest {
        val source = ScriptedByteSource(
            mapOf("img_key1" to ByteArray(64) { 1 }, "img_key2" to null),
        )
        val file = fileWith(descriptor("img_key1"), descriptor("img_key2"))

        val result = resendablePayloads(file, driveId, source, fileOps)

        assertEquals(listOf("img_key1"), result.payloads.map { it.key })
        assertEquals(listOf("img_key2"), result.unavailableKeys)
    }

    @Test
    fun `descriptor without iv is unavailable`() = runTest {
        val source = ScriptedByteSource(mapOf("img_key1" to ByteArray(64) { 1 }))
        val file = fileWith(descriptor("img_key1", withIv = false))

        val result = resendablePayloads(file, driveId, source, fileOps)

        assertTrue(result.payloads.isEmpty())
        assertEquals(listOf("img_key1"), result.unavailableKeys)
    }

    @Test
    fun `a throwing byte source marks the key unavailable`() = runTest {
        val source = ScriptedByteSource(
            mapOf("img_key1" to ByteArray(64) { 1 }),
            throwOnKey = "img_key1",
        )
        val file = fileWith(descriptor("img_key1"))

        val result = resendablePayloads(file, driveId, source, fileOps)

        assertTrue(result.payloads.isEmpty())
        assertEquals(listOf("img_key1"), result.unavailableKeys)
    }

    @Test
    fun `excluded keys are neither recovered nor reported unavailable`() = runTest {
        val source = ScriptedByteSource(mapOf("img_key1" to ByteArray(64) { 1 }))
        val file = fileWith(
            descriptor(ChatProtocol.DefaultPayloadKey),
            descriptor("img_key1"),
        )

        val result = resendablePayloads(
            file, driveId, source, fileOps,
            excludeKeys = setOf(ChatProtocol.DefaultPayloadKey),
        )

        assertEquals(listOf("img_key1"), result.payloads.map { it.key })
        assertTrue(result.unavailableKeys.isEmpty())
        assertEquals(
            listOf("img_key1"), source.payloadRequests,
            "the excluded overflow key must not even be requested",
        )
    }
}
