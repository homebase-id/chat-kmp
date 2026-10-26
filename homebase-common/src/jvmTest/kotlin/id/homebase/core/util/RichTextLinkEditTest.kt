package id.homebase.core.util

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RichTextLinkEditTest {

    private val markdown = "before [label](https://a.test) after"

    private fun state(): RichTextState = RichTextState().applyMarkDownContent(markdown)

    private fun RichTextState.caretAt(offset: Int) {
        selection = TextRange(offset)
    }

    @Test
    fun `link is found at its start middle and end and not outside`() {
        val s = state()
        val start = s.annotatedString.text.indexOf("label")
        val end = start + "label".length
        for (offset in listOf(start + 1, start + 3, end)) {
            s.caretAt(offset)
            val link = assertNotNull(s.linkAtSelection(), "offset $offset")
            assertEquals("https://a.test", link.url)
            assertEquals("label", link.text)
        }
        s.caretAt(1)
        assertNull(s.linkAtSelection())
        s.caretAt(s.annotatedString.text.length)
        assertNull(s.linkAtSelection())
    }

    @Test
    fun `update changes the url and leaves surrounding text identical`() {
        val s = state()
        s.caretAt(s.annotatedString.text.indexOf("label") + 2)
        s.updateLink("https://b.test")
        assertEquals("before [label](https://b.test) after", s.toMessageMarkdown())
    }

    @Test
    fun `remove leaves the plain label`() {
        val s = state()
        s.caretAt(s.annotatedString.text.indexOf("label") + 2)
        s.removeLink()
        assertEquals("before label after", s.toMessageMarkdown())
    }

    @Test
    fun `pasting a url over a selection labels it`() {
        val s = RichTextState().applyMarkDownContent("see docs now")
        val start = s.annotatedString.text.indexOf("docs")
        s.selection = TextRange(start, start + 4)
        assertEquals(true, s.pasteUrlOverSelection("https://x.test"))
        assertEquals("see [docs](https://x.test) now", s.toMessageMarkdown())
    }

    @Test
    fun `a non url paste is left to the editor`() {
        val s = RichTextState().applyMarkDownContent("see docs now")
        val start = s.annotatedString.text.indexOf("docs")
        s.selection = TextRange(start, start + 4)
        assertEquals(false, s.pasteUrlOverSelection("not a url"))
        assertEquals(false, s.pasteUrlOverSelection("https://x.test and more"))
        assertEquals("see docs now", s.toMessageMarkdown())
        s.selection = TextRange(start)
        assertEquals(false, s.pasteUrlOverSelection("https://x.test"))
    }
}
