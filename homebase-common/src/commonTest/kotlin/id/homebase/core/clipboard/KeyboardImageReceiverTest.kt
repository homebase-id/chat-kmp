package id.homebase.core.clipboard

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardImageReceiverTest {

    @Test
    fun acceptsAdvertisedImageTypes() {
        keyboardImageMimeTypes.forEach { assertTrue(acceptsKeyboardImage(listOf(it)), it) }
        assertTrue(acceptsKeyboardImage(listOf("IMAGE/GIF")))
        assertTrue(acceptsKeyboardImage(listOf("text/plain", "image/webp")))
    }

    @Test
    fun rejectsTypesTheEditorDoesNotAdvertise() {
        assertFalse(acceptsKeyboardImage(emptyList()))
        assertFalse(acceptsKeyboardImage(listOf("text/plain")))
        assertFalse(acceptsKeyboardImage(listOf("video/mp4")))
        assertFalse(acceptsKeyboardImage(listOf("image/heic")))
    }

    @Test
    fun readsWholeImageUpToTheCap() {
        val bytes = ByteArray(16) { it.toByte() }
        assertContentEquals(bytes, readKeyboardImage(Buffer().apply { write(bytes) }, maxBytes = 16))
    }

    @Test
    fun dropsImageOverTheCapInsteadOfTruncating() {
        assertNull(readKeyboardImage(Buffer().apply { write(ByteArray(17)) }, maxBytes = 16))
    }

    @Test
    fun dropsEmptyImage() {
        assertNull(readKeyboardImage(Buffer(), maxBytes = 16))
    }
}
