package id.homebase.chat.widget

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.image.MediaQuality
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The badge policy: only an explicitly recorded [MediaQuality.HIGH] shows an HD badge.
 *
 * Absent is "not recorded", never "standard" — every photo sent before the flag shipped
 * reads as absent, and many of those were HD. Absent and standard therefore render
 * identically (no badge), which is the intended outcome: a badge on the common case is
 * noise, and a *missing* badge on an old HD photo is far better than a wrong one.
 */
class MediaQualityBadgeTest {

    private fun image(descriptorContent: String?, contentType: String = "image/jpeg") =
        PayloadDescriptor(
            key = "chat_web0",
            contentType = contentType,
            descriptorContent = descriptorContent,
        )

    @Test
    fun highQuality_isBadged() {
        val wire = DescriptorContent.descriptorContentFromImage(
            isSticker = false,
            quality = MediaQuality.HIGH,
        )
        assertTrue(image(wire).isHighQualityImage())
    }

    @Test
    fun explicitStandardQuality_isNotBadged() {
        val wire = DescriptorContent.descriptorContentFromImage(
            isSticker = false,
            quality = MediaQuality.STANDARD,
        )
        assertFalse(image(wire).isHighQualityImage())
    }

    @Test
    fun legacyBlankDescriptor_isNotBadged() {
        // Every image sent before the flag shipped.
        assertFalse(image("").isHighQualityImage())
    }

    @Test
    fun nullDescriptor_isNotBadged() {
        assertFalse(image(null).isHighQualityImage())
    }

    @Test
    fun stickerDescriptorWithoutQuality_isNotBadged() {
        val wire = DescriptorContent.descriptorContentFromImage(
            isSticker = true,
            format = "image/png",
        )
        assertFalse(image(wire, contentType = "image/png").isHighQualityImage())
    }

    @Test
    fun malformedDescriptor_isNotBadged_noThrow() {
        assertFalse(image("{not json").isHighQualityImage())
    }

    @Test
    fun unknownQualityValue_isNotBadged() {
        // A future client could record a quality this build has never heard of; coercing
        // it to a badge would be a guess.
        assertFalse(image("""{"isSticker":false,"quality":"ultra"}""").isHighQualityImage())
    }

    @Test
    fun linkPreviewJsonArrayDescriptor_isNotBadged() {
        // chat_links / chat_loc payloads are image/* but store a JSON array here.
        assertFalse(image("""[{"url":"https://example.com"}]""").isHighQualityImage())
    }

    @Test
    fun videoPayload_isNotBadged() {
        val descriptor = PayloadDescriptor(
            key = "chat_vid0",
            contentType = "video/mp4",
            descriptorContent = """{"mimeType":"video/mp4","isSegmented":false}""",
        )
        assertFalse(descriptor.isHighQualityImage())
    }
}
