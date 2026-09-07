package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.MediaQuality
import id.homebase.chat.widget.isHighQualityImage
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Send-side half of the HD indicator: MessageAttachmentBuilder records
 * [MediaQuality.HIGH] on an ordinary photo's payload descriptor and records nothing
 * otherwise, so that only a deliberate HD send is badgeable by the receiver.
 */
class MessageAttachmentBuilderQualityTest {

    private fun opaqueJpeg(w: Int = 64, h: Int = 64): ByteArray {
        val info = ImageInfo(
            colorInfo = ColorInfo(ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
            width = w,
            height = h,
        )
        val bytes = ByteArray(w * h * 4)
        for (i in bytes.indices step 4) {
            bytes[i] = 0
            bytes[i + 1] = (0x80).toByte()
            bytes[i + 2] = (0xFF).toByte()
            bytes[i + 3] = (0xFF).toByte()
        }
        return Image.makeRaster(info, bytes, w * 4)
            .encodeToData(EncodedImageFormat.JPEG)?.bytes ?: error("encode failed")
    }

    private fun fakeFsServing(filePath: String, bytes: ByteArray) =
        object : FileOperationsProvider {
            override fun getCacheDirectory(): String = "/tmp/test-cache"
            override fun openFileInput(path: String): InputProvider = fail("openFileInput")
            override suspend fun readFileBytes(path: String): ByteArray {
                assertEquals(filePath, path)
                return bytes
            }

            override fun deleteTempFile(path: String): Boolean = false
            override fun getFileSize(path: String): Long = bytes.size.toLong()
            override suspend fun writeBytesToTempFile(
                bytes: ByteArray, prefix: String, suffix: String,
            ): String = fail("writeBytesToTempFile")

            override suspend fun writeBytesToShareOutboundFile(
                bytes: ByteArray, suffix: String,
            ): String = fail("writeBytesToShareOutboundFile")

            override suspend fun writeStream(path: String, data: Flow<ByteArray>) =
                fail<Unit>("writeStream")

            private fun <T> fail(name: String): T =
                throw UnsupportedOperationException("$name should not be called from this test")
        }

    /** Rebuild the descriptor the way a receiver would. */
    private fun received(descriptorContent: String?): PayloadDescriptor =
        PayloadDescriptor(
            key = "chat_web0",
            contentType = "image/jpeg",
            descriptorContent = descriptorContent,
        )

    private suspend fun sendPhoto(
        quality: MediaQuality,
        forceSticker: Boolean = false,
    ): String? {
        val path = "/tmp/photo-${quality.code}-$forceSticker.jpg"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(
                filePath = path,
                contentType = "image/jpeg",
                forceSticker = forceSticker,
            ),
            fileOperationsProvider = fakeFsServing(path, opaqueJpeg()),
            payloadKey = "chat_web0",
            mediaQuality = quality,
        )
        return bundle.payloads.single().descriptorContent
    }

    @Test
    fun highQualityPhoto_recordsHighOnTheWire() = runTest {
        val descriptorContent = sendPhoto(MediaQuality.HIGH)

        val info = received(descriptorContent).descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile, "Expected ImageFile, got $info")
        assertEquals(MediaQuality.HIGH, info.quality)
        assertFalse(info.isSticker, "A photo sent in HD is still not a sticker")
        assertTrue(received(descriptorContent).isHighQualityImage())
    }

    @Test
    fun standardQualityPhoto_recordsNothing_andIsNotBadged() = runTest {
        val descriptorContent = sendPhoto(MediaQuality.STANDARD)

        // STANDARD keeps the legacy blank descriptor, so it is indistinguishable on the
        // wire from a photo sent before the flag existed — both render no badge.
        assertEquals("", descriptorContent)
        val info = received(descriptorContent).descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile)
        assertNull(info.quality)
        assertFalse(received(descriptorContent).isHighQualityImage())
    }

    @Test
    fun sticker_neverRecordsQuality_evenWhenSentAsHigh() = runTest {
        // A sticker's bytes are bounded at 512px regardless of the toggle, so "HD" would
        // be a false claim.
        val descriptorContent = sendPhoto(MediaQuality.HIGH, forceSticker = true)

        val info = received(descriptorContent).descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile)
        assertTrue(info.isSticker)
        assertNull(info.quality)
        assertFalse(received(descriptorContent).isHighQualityImage())
    }
}
