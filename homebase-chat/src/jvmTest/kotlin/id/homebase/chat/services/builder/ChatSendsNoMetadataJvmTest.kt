package id.homebase.chat.services.builder

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.ImageMetadataScrubber
import id.homebase.api.image.MediaQuality
import id.homebase.api.video.FFmpegBinaryManager
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadBundleEncryptionService
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// What chat actually sends, decrypted: no photo or video carries the sender's location, at any quality.
// Photos' side, which keeps it, is OutboxBackupUploaderTest.
class ChatSendsNoMetadataJvmTest {

    private val fileOps = JvmFileOperationsProvider()
    private val dir = kotlin.io.path.createTempDirectory("hb-chat-metadata").toFile()
    private val filmedAt = "+37.7749-122.4194/"
    private val encryptor = PayloadBundleEncryptionService(fileOps, VideoPayloadProcessor(fileOps), EventBus())

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }.path

    // A camera JPEG: an EXIF block whose GPS IFD holds a marker.
    private fun geotaggedJpeg(w: Int, h: Int): ByteArray {
        val info = ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB), w, h)
        val pixels = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else ((i / 4) % 251).toByte() }
        val plain = Image.makeRaster(info, pixels, w * 4).encodeToData(EncodedImageFormat.JPEG)!!.bytes
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Int) = ByteArray(4) { (v ushr (8 * it)).toByte() }
        fun entry(tag: Int, type: Int, count: Int, value: ByteArray) = le16(tag) + le16(type) + le32(count) + value.copyOf(4)
        val ifd0 = le16(1) + entry(0x8825, 4, 1, le32(26)) + le32(0)
        val gps = le16(1) + entry(0x0002, 2, 8, le32(44)) + le32(0)
        val tiff = "II".encodeToByteArray() + le16(42) + le32(8) + ifd0 + gps + "GPS-7749".encodeToByteArray()
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    private suspend fun sent(path: String, contentType: String, quality: MediaQuality): Pair<PayloadBundle, KeyHeader> {
        val bundle = MessageAttachmentBuilder.buildSingle(AttachmentInput(path, contentType), fileOps, "chat_web0", quality)
        val key = KeyHeader.newRandom16()
        val encrypted = encryptor.encryptBundle(Uuid.random(), bundle, key.aesKey, kotlinx.coroutines.GlobalScope)
        return encrypted to key
    }

    private suspend fun PayloadBundle.opened(key: KeyHeader): ByteArray =
        payloads.single { it.key == "chat_web0" }.let { KeyHeader(it.iv!!, key.aesKey).decrypt(File(it.filePath).readBytes()) }

    private suspend fun PayloadBundle.openedThumbnails(key: KeyHeader): List<ByteArray> {
        val iv = payloads.single { it.key == "chat_web0" }.iv!!
        return thumbnails.map { KeyHeader(iv, key.aesKey).decrypt(it.thumbnailBytes) }
    }

    @Test
    fun aPhotoSentAtAnyQualityCarriesNoMetadataInItsImageOrThumbnails() = runTest {
        val source = file("geo.jpg", geotaggedJpeg(5000, 3000))
        assertTrue(ImageMetadataScrubber.readBlocks(File(source).readBytes()) != null, "the fixture carries EXIF")
        for (quality in MediaQuality.entries) {
            val (bundle, key) = sent(source, "image/jpeg", quality)
            assertNull(ImageMetadataScrubber.readBlocks(bundle.opened(key)), "$quality: the photo chat sends has no EXIF")
            val thumbs = bundle.openedThumbnails(key)
            assertTrue(thumbs.isNotEmpty())
            thumbs.forEach { assertNull(ImageMetadataScrubber.readBlocks(it), "$quality: nor does any thumbnail") }
        }
    }

    @Test
    fun aPublicPhotoIsStagedWithoutMetadataAtAnyQuality() = runTest {
        val source = file("public.jpg", geotaggedJpeg(5000, 3000))
        for (quality in MediaQuality.entries) {
            val bundle = MessageAttachmentBuilder.buildSingle(AttachmentInput(source, "image/jpeg"), fileOps, "chat_web0", quality)
            val staged = encryptor.stageBundle(Uuid.random(), bundle)
            assertNull(ImageMetadataScrubber.readBlocks(File(staged.payloads.single().filePath).readBytes()), "$quality: a public photo goes out without EXIF")
        }
    }

    @Test
    fun aPublicVideoIsRefusedRatherThanSentUnprocessed() = runTest {
        val clip = file("public.mp4", this::class.java.getResourceAsStream("/test_videos/sample.mp4")!!.readBytes())
        val bundle = MessageAttachmentBuilder.buildSingle(AttachmentInput(clip, "video/mp4"), fileOps, "chat_web0", MediaQuality.entries.first())
        assertFailsWith<IllegalArgumentException> { encryptor.stageBundle(Uuid.random(), bundle) }
    }

    private fun ffmpeg(vararg args: String): String {
        val process = FFmpegBinaryManager.processBuilder(*args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { out }
        return out
    }

    private fun locationOf(video: ByteArray): String? {
        val path = file("probe_${video.size}.mp4", video)
        return ffmpeg(FFmpegBinaryManager.ffprobePath(), "-v", "error", "-show_entries", "format_tags=location", "-of", "default=noprint_wrappers=1:nokey=1", path)
            .trim().ifEmpty { null }
    }

    @Test
    fun aVideoSentAtAnyQualityCarriesNoLocation() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        // The 26 KB test clip, tagged as a camera would with where it was filmed.
        val sample = file("sample.mp4", this::class.java.getResourceAsStream("/test_videos/sample.mp4")!!.readBytes())
        val clip = File(dir, "tagged.mov").path
        ffmpeg(FFmpegBinaryManager.ffmpegPath(), "-y", "-i", sample, "-c", "copy", "-metadata", "location=$filmedAt", clip)
        assertEquals(filmedAt, locationOf(File(clip).readBytes()), "the fixture carries a location")

        for (quality in MediaQuality.entries) {
            val (bundle, key) = sent(clip, "video/quicktime", quality)
            assertNull(locationOf(bundle.opened(key)), "$quality: the video chat sends has no location")
        }
    }
}
