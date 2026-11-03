package id.homebase.core.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PastedTextTest {

    private val image = byteArrayOf(1, 2, 3)

    @Test
    fun textAloneIsPasted() {
        assertEquals("hello", pastedText(image = null, text = "hello"))
    }

    @Test
    fun imageAloneInsertsNoText() {
        assertNull(pastedText(image = image, text = null))
    }

    @Test
    fun imageWithTextKeepsTheText() {
        assertEquals("look at this", pastedText(image = image, text = "look at this"))
    }

    @Test
    fun blankTextIsNotPasted() {
        assertNull(pastedText(image = null, text = "  \n"))
        assertNull(pastedText(image = image, text = ""))
    }

    @Test
    fun imageAddressBesideTheImageIsDropped() {
        assertNull(pastedText(image = image, text = "https://example.com/cat.jpg"))
        assertNull(pastedText(image = image, text = " file:///Users/me/cat.png "))
    }

    @Test
    fun imageFileNameBesideTheImageIsDropped() {
        assertNull(pastedText(image = image, text = "Screenshot 2026-10-26 at 10.00.00.png"))
        assertNull(pastedText(image = image, text = "/Users/me/Pictures/IMG_0001.HEIC"))
    }

    @Test
    fun addressWithoutAnImageIsPasted() {
        assertEquals("https://example.com", pastedText(image = null, text = "https://example.com"))
        assertEquals("cat.png", pastedText(image = null, text = "cat.png"))
    }

    @Test
    fun multiLineTextMentioningAFileIsKept() {
        val text = "Here it is\ncat.png"
        assertEquals(text, pastedText(image = image, text = text))
    }
}
