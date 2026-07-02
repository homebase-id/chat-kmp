package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The #845 S4 regression: a non-segmented MP4 must be STREAMED to a disposable
 * `hbvid_res_*` temp — never pulled whole into RAM — except on web
 * (`preferBytes = true`), where the guarded byte path is deliberate.
 */
class VideoContentResolverTest {

    private val driveId = Uuid.fromLongs(0x1L, 0x2L)
    private val fileId = Uuid.fromLongs(0x3L, 0x4L)
    private val payloadKey = "vid_payload"

    private val fileOps = object : FileOperationsProvider {
        override fun getCacheDirectory() = "/cache"
        override fun openFileInput(path: String): InputProvider = error("not used")
        override suspend fun readFileBytes(path: String): ByteArray = error("not used")
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    }

    private fun playerData(stub: VideoMetadata) = VideoPlayerData(
        fileId = fileId,
        driveId = driveId,
        payloadKey = payloadKey,
        keyHeader = KeyHeader.empty(),
        descriptorContent = OdinSystemSerializer.serialize(stub),
    )

    private val mp4Stub = VideoMetadata(
        mimeType = "video/mp4",
        isDescriptorContentComplete = true,
        isSegmented = false,
        fileSize = 300L * 1024 * 1024, // foreign large non-segmented mp4 — the S4 shape
    )

    @Test
    fun nonSegmentedMp4_streamsToHbvidTemp_neverBuffersBytes() = runTest {
        val fake = FakeVideoPrefetchDriveAccess(getPayloadResponses = mapOf(payloadKey to "unused"))

        val content = resolveVideoContent(playerData(mp4Stub), fake, fileOps = fileOps)

        val mp4 = assertIs<VideoContent.Mp4File>(content)
        assertTrue(
            mp4.filePath.startsWith("/cache/hbvid_res_") && mp4.filePath.endsWith(".mp4"),
            "must stream into the swept hbvid_res_* pattern: ${mp4.filePath}",
        )
        assertEquals(
            1,
            fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.StreamPayloadDecryptedToPath>().size,
        )
        assertTrue(
            fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.GetPayloadBytesDecrypted>().isEmpty(),
            "the payload must never be byte-buffered on the file path (calls=${fake.calls})",
        )
    }

    @Test
    fun nonSegmentedMp4_preferBytes_returnsGuardedByteVariant() = runTest {
        val fake = FakeVideoPrefetchDriveAccess(getPayloadResponses = mapOf(payloadKey to "tiny-mp4-bytes"))

        val content = resolveVideoContent(playerData(mp4Stub), fake, fileOps = fileOps, preferBytes = true)

        val mp4 = assertIs<VideoContent.Mp4Bytes>(content)
        assertContentEquals("tiny-mp4-bytes".encodeToByteArray(), mp4.bytes)
        assertTrue(
            fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.StreamPayloadDecryptedToPath>().isEmpty(),
        )
    }

    @Test
    fun segmentedWithPlaylist_staysOnHlsBranch() = runTest {
        val hlsStub = VideoMetadata(
            mimeType = "application/vnd.apple.mpegurl",
            isDescriptorContentComplete = true,
            isSegmented = true,
            hlsPlaylist = "#EXTM3U\n#EXT-X-BYTERANGE:1024@0\n",
        )
        val fake = FakeVideoPrefetchDriveAccess()

        val content = resolveVideoContent(playerData(hlsStub), fake, fileOps = fileOps)

        assertIs<VideoContent.Hls>(content)
        assertTrue(fake.calls.isEmpty(), "HLS resolution must not fetch any payload bytes")
    }
}
