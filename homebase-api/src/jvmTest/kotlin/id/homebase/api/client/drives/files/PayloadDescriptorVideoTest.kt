package id.homebase.api.client.drives.files

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the descriptor surface that chat bubbles use to read video duration / isHls
 * without ever opening the underlying file. If this regresses, every video bubble
 * stops showing duration and the chat module starts having to deserialize
 * VideoMetadata directly — which is exactly the layering breach we abstracted away.
 */
class PayloadDescriptorVideoTest {

    private fun videoDescriptor(metadata: VideoMetadata): PayloadDescriptor =
        PayloadDescriptor(
            key = "chat_web0",
            contentType = "video/mp4",
            descriptorContent = OdinSystemSerializer.serialize(metadata),
        )

    @Test
    fun videoDescriptor_returnsVideoFile_withDurationAndIsSegmented() {
        val descriptor = videoDescriptor(
            VideoMetadata(
                mimeType = "video/mp4",
                isSegmented = false,
                duration = 4_200f,
            )
        )

        val info = descriptor.descriptorInfo()
        assertTrue(info is DescriptorContent.VideoFile, "Expected VideoFile, got $info")
        assertEquals(4_200L, info.durationMs)
        assertEquals(false, info.isSegmented)
    }

    @Test
    fun videoDescriptor_surfacesTechnicalMetadata_forDebugOverlay() {
        // The inline debug overlay reads codec/resolution/bit-depth/HDR/bitrate
        // off the VideoFile descriptor; pin that they propagate from VideoMetadata.
        val descriptor = videoDescriptor(
            VideoMetadata(
                mimeType = "video/mp4",
                isSegmented = false,
                duration = 5_000f,
                fileSize = 1_234_567L,
                codec = "h264",
                widthPx = 1280,
                heightPx = 720,
                bitDepth = 10,
                isHdr = true,
                videoBitrateBps = 2_500_000L,
            )
        )

        val info = descriptor.descriptorInfo()
        assertTrue(info is DescriptorContent.VideoFile)
        assertEquals("h264", info.codec)
        assertEquals(1280, info.widthPx)
        assertEquals(720, info.heightPx)
        assertEquals(10, info.bitDepth)
        assertEquals(true, info.isHdr)
        assertEquals(2_500_000L, info.videoBitrateBps)
        assertEquals(1_234_567L, info.fileSizeBytes)
        assertEquals("video/mp4", info.mimeType)
    }

    @Test
    fun videoDescriptor_isSegmentedTrue_propagates() {
        val descriptor = videoDescriptor(
            VideoMetadata(
                mimeType = "application/vnd.apple.mpegurl",
                isSegmented = true,
                duration = 30_000f,
            )
        )

        val info = descriptor.descriptorInfo()
        assertTrue(info is DescriptorContent.VideoFile)
        assertEquals(true, info.isSegmented)
    }

    @Test
    fun videoDescriptor_zeroDuration_isReportedAsNull() {
        // The takeIf { it > 0 } guard exists so a missing/0 duration in the
        // descriptor doesn't render "0:00" on the bubble.
        val descriptor = videoDescriptor(
            VideoMetadata(
                mimeType = "video/mp4",
                isSegmented = false,
                duration = 0f,
            )
        )

        val info = descriptor.descriptorInfo()
        assertTrue(info is DescriptorContent.VideoFile)
        assertNull(info.durationMs)
    }

    @Test
    fun videoDescriptor_nullDescriptorContent_isEmpty() {
        val descriptor = PayloadDescriptor(
            key = "chat_web0",
            contentType = "video/mp4",
            descriptorContent = null,
        )

        assertEquals(DescriptorContent.Empty, descriptor.descriptorInfo())
    }

    @Test
    fun videoDescriptor_malformedJson_falsBackToEmpty() {
        // A malformed descriptor must not crash the bubble — it just shows no badge.
        val descriptor = PayloadDescriptor(
            key = "chat_web0",
            contentType = "video/mp4",
            descriptorContent = "{this is not valid json}",
        )

        assertEquals(DescriptorContent.Empty, descriptor.descriptorInfo())
    }

    @Test
    fun nonVideoDescriptor_isUnaffected() {
        // Make sure the new VideoFile branch only fires when contentType matches.
        val imageDescriptor = PayloadDescriptor(
            key = "chat_web0",
            contentType = "image/jpeg",
            descriptorContent = "filename.jpg",
        )

        val info = imageDescriptor.descriptorInfo()
        assertTrue(info is DescriptorContent.File)
        assertEquals("filename.jpg", info.name)
    }
}
