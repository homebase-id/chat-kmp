package id.homebase.chat.widget

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.image.MediaQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The badge policy: only an explicitly recorded [MediaQuality.ORIGINAL] shows an HD badge.
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
    fun original_isBadgedOriginal() {
        val wire = DescriptorContent.descriptorContentFromImage(
            isSticker = false,
            quality = MediaQuality.ORIGINAL,
        )
        assertEquals(MediaQuality.ORIGINAL, image(wire).badgedQuality())
    }

    @Test
    fun hd_isBadgedHd() {
        val wire = DescriptorContent.descriptorContentFromImage(
            isSticker = false,
            quality = MediaQuality.HD,
        )
        assertEquals(MediaQuality.HD, image(wire).badgedQuality())
    }

    @Test
    fun explicitStandardQuality_isNotBadged() {
        val wire = DescriptorContent.descriptorContentFromImage(
            isSticker = false,
            quality = MediaQuality.STANDARD,
        )
        assertNull(image(wire).badgedQuality())
    }

    @Test
    fun legacyBlankDescriptor_isNotBadged() {
        // Every image sent before the flag shipped.
        assertNull(image("").badgedQuality())
    }

    @Test
    fun nullDescriptor_isNotBadged() {
        assertNull(image(null).badgedQuality())
    }

    @Test
    fun stickerDescriptorWithoutQuality_isNotBadged() {
        val wire = DescriptorContent.descriptorContentFromImage(
            isSticker = true,
            format = "image/png",
        )
        assertNull(image(wire, contentType = "image/png").badgedQuality())
    }

    @Test
    fun malformedDescriptor_isNotBadged_noThrow() {
        assertNull(image("{not json").badgedQuality())
    }

    @Test
    fun unknownQualityValue_isNotBadged() {
        // A future client could record a quality this build has never heard of; coercing
        // it to a badge would be a guess.
        assertNull(image("""{"isSticker":false,"quality":"ultra"}""").badgedQuality())
    }

    @Test
    fun linkPreviewJsonArrayDescriptor_isNotBadged() {
        // chat_links / chat_loc payloads are image/* but store a JSON array here.
        assertNull(image("""[{"url":"https://example.com"}]""").badgedQuality())
    }

    @Test
    fun videoPayload_isNotBadged() {
        val descriptor = PayloadDescriptor(
            key = "chat_vid0",
            contentType = "video/mp4",
            descriptorContent = """{"mimeType":"video/mp4","isSegmented":false}""",
        )
        assertNull(descriptor.badgedQuality())
    }
}
