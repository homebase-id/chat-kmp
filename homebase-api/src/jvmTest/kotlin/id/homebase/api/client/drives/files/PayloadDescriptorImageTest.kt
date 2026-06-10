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
 * non-sticker [DescriptorContent.ImageFile] (never throw, never accidentally cut out a photo).
 * `filename()` returns null for a NON-sticker image (download name falls back to the payload
 * key, the legacy behaviour), and "StickerFile.<ext>" for a sticker so a saved sticker is named
 * neutrally instead of leaking the sender's original camera-roll filename.
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
    fun nonStickerImageFilename_isNull_soDownloadNameFallsBackToPayloadKey() {
        // A non-sticker image carries no usable name — filename() returns null so
        // MediaDownloadHandler keeps falling back to payload.key (legacy "" behaviour).
        assertNull(imageDescriptor(DescriptorContent.descriptorContentFromImage(false)).filename())
        assertNull(imageDescriptor("").filename())
    }

    @Test
    fun stickerFilename_isStickerFileWithDetectedExtension() {
        // A sticker downloads as "StickerFile.<ext>" — the detected format drives the extension,
        // so the sender's original filename (e.g. IMG_1234.png) is never leaked on download.
        assertEquals(
            "StickerFile.png",
            imageDescriptor(
                DescriptorContent.descriptorContentFromImage(isSticker = true, format = "image/png")
            ).filename(),
        )
        assertEquals(
            "StickerFile.webp",
            imageDescriptor(
                DescriptorContent.descriptorContentFromImage(isSticker = true, format = "image/webp")
            ).filename(),
        )
        assertEquals(
            "StickerFile.jpg",
            imageDescriptor(
                DescriptorContent.descriptorContentFromImage(isSticker = true, format = "image/jpeg")
            ).filename(),
        )
    }

    @Test
    fun legacyStickerWithoutFormat_downloadsAsStickerFilePng() {
        // A sticker descriptor from an older sender (no "format" key) still gets a usable name:
        // the extension defaults to png rather than leaving the file unnamed.
        assertEquals(
            "StickerFile.png",
            imageDescriptor("""{"isSticker":true}""").filename(),
        )
    }
}
