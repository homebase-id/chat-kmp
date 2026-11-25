package id.homebase.upload

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.crypto.newHasher
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.video.SegmentedVideo
import id.homebase.api.video.VideoCompressor
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.api.video.VideoProber
import id.homebase.api.video.VideoProgressListener
import id.homebase.api.video.VideoQuality
import id.homebase.api.video.VideoTrackInfo
import id.homebase.api.client.KeyHeader
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** A public post's video is compressed like any other, then sent unencrypted from outbox staging. */
class PublicVideoStageTest {
    private val files = JvmFileOperationsProvider()
    private val compressed = ByteArray(4096) { (it * 7).toByte() }

    private val compressor = object : VideoCompressor {
        var quality: VideoQuality? = null
        override suspend fun compress(
            inputPath: String,
            onProgress: VideoProgressListener?,
            trimStartMs: Long?,
            trimEndMs: Long?,
            quality: VideoQuality,
            keepMetadata: Boolean,
        ): String {
            this.quality = quality
            return files.writeBytesToTempFile(compressed, "compressed_", ".mp4")
        }

        override suspend fun segmentAndEncrypt(inputPath: String, keyHeader: KeyHeader?, onProgress: VideoProgressListener?): SegmentedVideo? =
            error("a 4 KB video is never segmented")
    }

    private val prober = object : VideoProber {
        override suspend fun getDurationMs(inputPath: String) = 5_000L
        override suspend fun getFfmpegVersion(): String? = null
        override suspend fun probeVideo(inputPath: String): VideoTrackInfo? = null
        override fun canRead(contentType: String) = true
    }

    private val service = PayloadBundleEncryptionService(files, VideoPayloadProcessor(files, compressor, prober), EventBus())

    private suspend fun source() = files.writeBytesToTempFile(ByteArray(10_000) { 1 }, "picked_", ".mp4")

    @Test
    fun aPublicVideoIsCompressedAndStagedUnencryptedBesideAPhoto() = runTest {
        val video = source()
        val photo = files.writeBytesToTempFile(ByteArray(300) { 9 }, "photo_", ".bin")
        val photoThumb = ThumbnailFile(pixelWidth = 20, pixelHeight = 20, thumbnailBytes = ByteArray(5) { 2 }, key = "pst_mdi1")
        val bundle = PayloadBundle(
            payloads = listOf(
                PayloadFile(key = "pst_mdi0", filePath = video, contentType = "video/mp4", videoQuality = VideoQuality.HIGH),
                PayloadFile(key = "pst_mdi1", filePath = photo, contentType = "image/png", stripMetadata = false),
            ),
            thumbnails = listOf(photoThumb),
            previewThumbs = emptyList(),
        )

        val staged = service.stageBundle(Uuid.random(), bundle, this)

        assertEquals(VideoQuality.HIGH, compressor.quality, "compressed to the app's setting, never sent raw")
        val sentVideo = staged.payloads.single { it.key == "pst_mdi0" }
        assertFalse(sentVideo.isPreEncrypted)
        assertNull(sentVideo.iv)
        val expected = PayloadHashAlgorithm.Blake3.newHasher().apply { update(compressed) }.digest()
        assertEquals("blake3", sentVideo.hash?.algorithm)
        assertContentEquals(expected, sentVideo.hash?.storedHash?.bytes)
        assertContentEquals(expected, sentVideo.hash?.contentHash?.bytes)
        assertNotEquals(video, sentVideo.filePath)
        assertContentEquals(compressed, File(sentVideo.filePath).readBytes())
        assertContentEquals(File(photo).readBytes(), File(staged.payloads.single { it.key == "pst_mdi1" }.filePath).readBytes())
        assertEquals(listOf(photoThumb), staged.thumbnails.filter { it.key == "pst_mdi1" })
        assertTrue(File(video).exists(), "the person's own file is never consumed")

        service.discard(staged)
        File(video).delete()
        File(photo).delete()
    }

    @Test
    fun aFailingVideoLeavesNothingStaged() = runTest {
        val video = source()
        val photo = files.writeBytesToTempFile(ByteArray(300) { 9 }, "photo_", ".bin")
        val failing = PayloadBundleEncryptionService(
            files,
            VideoPayloadProcessor(files, object : VideoCompressor by compressor {
                override suspend fun compress(
                    inputPath: String, onProgress: VideoProgressListener?, trimStartMs: Long?, trimEndMs: Long?, quality: VideoQuality, keepMetadata: Boolean,
                ): String = error("transcode failed")
            }, prober),
            EventBus(),
        )
        val before = File(files.getOutboxStagingDirectory()).listFiles()?.map { it.name }.orEmpty().toSet()

        assertFailsWith<IllegalStateException> {
            failing.stageBundle(
                Uuid.random(),
                PayloadBundle(
                    payloads = listOf(
                        PayloadFile(key = "pst_mdi1", filePath = photo, contentType = "image/png"),
                        PayloadFile(key = "pst_mdi0", filePath = video, contentType = "video/mp4"),
                    ),
                    thumbnails = emptyList(),
                    previewThumbs = emptyList(),
                ),
                this,
            )
        }

        val after = File(files.getOutboxStagingDirectory()).listFiles()?.map { it.name }.orEmpty().toSet()
        assertEquals(before, after, "the photo copied before the failure is deleted")
        File(video).delete()
        File(photo).delete()
    }
}
