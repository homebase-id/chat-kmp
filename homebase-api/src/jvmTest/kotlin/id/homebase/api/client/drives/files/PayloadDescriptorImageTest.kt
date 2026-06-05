package id.homebase.api.client.drives.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the per-image sticker descriptor that rides on `PayloadDescriptor.descriptorContent`.
 * The bubble reads this flag to decide whether to drop its opaque backdrop. The contract is
 * additive and backward-compatible: a blank, null, or malformed descriptor must yield a
 * non-sticker [DescriptorContent.ImageFile] (never throw, never accidentally cut out a photo),
 * and `filename()` must return null for an image so the download name still falls back to the
 * payload key.
 */
class PayloadDescriptorImageTest {

    private fun imageDescriptor(descriptorContent: String?): PayloadDescriptor =
        PayloadDescriptor(
            key = "chat_web0",
            contentType = "image/png",
            descriptorContent = descriptorContent,
        )

    @Test
    fun stickerDescriptor_roundTrips_true() {
        val serialized = DescriptorContent.descriptorContentFromImage(isSticker = true)
        val info = imageDescriptor(serialized).descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile, "Expected ImageFile, got $info")
        assertTrue(info.isSticker, "Expected isSticker=true")
    }

    @Test
    fun nonStickerDescriptor_roundTrips_false() {
        val serialized = DescriptorContent.descriptorContentFromImage(isSticker = false)
        val info = imageDescriptor(serialized).descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile)
        assertFalse(info.isSticker)
    }

    @Test
    fun blankDescriptor_isNonSticker() {
        // Legacy image payloads wrote descriptorContent = "".
        val info = imageDescriptor("").descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile, "Expected ImageFile, got $info")
        assertFalse(info.isSticker)
    }

    @Test
    fun nullDescriptor_isEmpty() {
        // null short-circuits to Empty at the top of descriptorInfo() (unchanged behaviour);
        // the image branch only runs for a present descriptor.
        val info = imageDescriptor(null).descriptorInfo()
        assertEquals(DescriptorContent.Empty, info)
    }

    @Test
    fun malformedDescriptor_isNonSticker_noThrow() {
        // A corrupt descriptor must not crash the bubble — opaque is the safe default.
        val info = imageDescriptor("{not valid json").descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile, "Expected ImageFile, got $info")
        assertFalse(info.isSticker)
    }

    @Test
    fun jsonArrayDescriptor_isNonSticker_noThrow() {
        // Single-payload link-preview (chat_links) and location-preview (chat_loc) bubbles
        // share the image/* contentType but store a JSON *array* (not an {"isSticker":...}
        // object) in descriptorContent. That must parse to a non-sticker ImageFile WITHOUT
        // attempting the object deserialize and WITHOUT logging a warning, since the array
        // shape is expected — not corruption. (Smoke test: the array shape doesn't throw.)
        val info = imageDescriptor("""[{"url":"https://example.com","title":"Hi"}]""")
            .descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile, "Expected ImageFile, got $info")
        assertFalse(info.isSticker)

        // Leading whitespace before the array bracket is still treated as a non-object.
        val padded = imageDescriptor("""   [{"url":"https://example.com"}]""").descriptorInfo()
        assertTrue(padded is DescriptorContent.ImageFile, "Expected ImageFile, got $padded")
        assertFalse(padded.isSticker)
    }

    @Test
    fun unknownKeysInDescriptor_areIgnored() {
        // Forward-compat: a future client might add fields; ignoreUnknownKeys keeps us parsing.
        val info = imageDescriptor("""{"isSticker":true,"futureField":42}""").descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile)
        assertTrue(info.isSticker)
    }

    @Test
    fun imageFilename_isNull_soDownloadNameFallsBackToPayloadKey() {
        // The sticker descriptor is NOT a filename — filename() must return null so
        // MediaDownloadHandler keeps falling back to payload.key (legacy "" behaviour).
        assertNull(imageDescriptor(DescriptorContent.descriptorContentFromImage(true)).filename())
        assertNull(imageDescriptor("").filename())
    }
}
