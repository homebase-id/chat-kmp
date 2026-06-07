package id.homebase.chat.conversationlist

import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the pasted-clipboard-image content-type derivation: the temp-file suffix is
 * sniffed from the magic bytes so the wire content-type (derived from the file name by
 * the send path) matches the actual bytes. The critical case is an animated GIF, which
 * must keep ".gif" -> image/gif so it is sent THUMBLESS and animates on the receiver.
 */
class ClipboardImageSuffixTest {

    // GIF89a header: 47 49 46 38 39 61
    private val gif89a = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
    )

    // GIF87a header: 47 49 46 38 37 61
    private val gif87a = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x37, 0x61,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
    )

    // PNG header: 89 50 4E 47
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    // JPEG header: FF D8 FF E0
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
    )

    @Test
    fun gif89aBytesGetGifSuffix() {
        assertEquals(".gif", clipboardImageSuffix(gif89a))
    }

    @Test
    fun gif87aBytesGetGifSuffix() {
        assertEquals(".gif", clipboardImageSuffix(gif87a))
    }

    @Test
    fun pngBytesGetPngSuffix() {
        assertEquals(".png", clipboardImageSuffix(png))
    }

    @Test
    fun jpegBytesGetJpgSuffix() {
        assertEquals(".jpg", clipboardImageSuffix(jpeg))
    }

    @Test
    fun unknownBytesFallBackToPng() {
        assertEquals(".png", clipboardImageSuffix(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    /**
     * End-to-end intent: the chosen suffix, fed through the same extension-based content
     * type lookup the send path uses (resolveContentType -> detectContentTypeFromExtensionOrHint),
     * yields image/gif for a pasted GIF. This is what was broken before — GIF bytes in a
     * ".png" temp file resolved to image/png and lost animation.
     */
    @Test
    fun gifSuffixResolvesToGifContentTypeViaFileName() {
        val suffix = clipboardImageSuffix(gif89a)
        val fileName = "clipboard_image$suffix"
        assertEquals("image/gif", detectContentTypeFromExtensionOrHint(fileName))
    }

    @Test
    fun pngSuffixResolvesToPngContentTypeViaFileName() {
        val suffix = clipboardImageSuffix(png)
        val fileName = "clipboard_image$suffix"
        assertEquals("image/png", detectContentTypeFromExtensionOrHint(fileName))
    }
}
