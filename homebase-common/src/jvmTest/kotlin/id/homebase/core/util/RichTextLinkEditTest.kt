package id.homebase.core.util

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `probing a caret past a link end leaves the selection unchanged`() {
        val s = state()
        val end = s.annotatedString.text.indexOf("label") + "label".length
        s.caretAt(end)
        assertNotNull(s.linkAtSelection())
        assertEquals(TextRange(end), s.selection)
    }

    @Test
    fun `looking for a link away from one keeps the caret's pending bold`() {
        val s = state()
        s.caretAt(2)
        s.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        assertNull(s.linkAtSelection())
        assertEquals(FontWeight.Bold, s.currentSpanStyle.fontWeight)
    }

    @Test
    fun `update and remove work from a caret past the link end and restore it`() {
        val s = state()
        val end = s.annotatedString.text.indexOf("label") + "label".length
        s.caretAt(end)
        s.updateLinkAtSelection("https://b.test")
        assertEquals(TextRange(end), s.selection)
        assertEquals("before [label](https://b.test) after", s.toMessageMarkdown())
        s.removeLinkAtSelection()
        assertEquals(TextRange(end), s.selection)
        assertEquals("before label after", s.toMessageMarkdown())
    }

    @Test
    fun `adding a link over a selection replaces it instead of duplicating`() {
        val s = RichTextState().applyMarkDownContent("see docs now")
        val start = s.annotatedString.text.indexOf("docs")
        s.selection = TextRange(start, start + 4)
        s.addLinkReplacingSelection("docs", "https://x.test")
        assertEquals("see [docs](https://x.test) now", s.toMessageMarkdown())
    }

    private fun selectDocs(): RichTextState {
        val s = RichTextState().applyMarkDownContent("see docs now")
        val start = s.annotatedString.text.indexOf("docs")
        s.selection = TextRange(start, start + 4)
        return s
    }

    @Test
    fun `async paste of a url over a selection labels it`() {
        val s = selectDocs()
        runBlocking { s.pasteOverSelectionAsync { "https://x.test" } }
        assertEquals("see [docs](https://x.test) now", s.toMessageMarkdown())
    }

    @Test
    fun `async paste of plain text replaces the selection`() {
        val s = selectDocs()
        runBlocking { s.pasteOverSelectionAsync { "notes" } }
        assertEquals("see notes now", s.toMessageMarkdown())
    }

    @Test
    fun `async paste with an empty clipboard keeps the selection text`() {
        val s = selectDocs()
        runBlocking { s.pasteOverSelectionAsync { null } }
        assertEquals("see docs now", s.toMessageMarkdown())
    }
}
