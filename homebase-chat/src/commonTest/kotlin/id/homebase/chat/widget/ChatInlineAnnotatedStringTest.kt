package id.homebase.chat.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatInlineAnnotatedStringTest {

    private val style = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Default)

    private fun build(content: String, mentions: MentionContext? = null): AnnotatedString {
        var result: AnnotatedString? = null
        // Capture inside a composition so the test works whether or not the
        // builder is @Composable. The builder applies withChatHardLineBreaks itself.
        runComposeUiTest {
            setContent { result = buildChatInlineAnnotatedString(content, style, Color.Black, mentions) }
            waitForIdle()
        }
        return result!!
    }

    @Test
    fun softLineBreakRendersAsNewline() {
        val a = build("alpha\nbeta")
        assertTrue(a.text.contains("\n"), "single newline must survive as a hard break")
        assertFalse(a.text.contains("alpha beta"), "must not collapse to a space")
    }

    @Test
    fun bareUrlIsAutolinked() {
        val a = build("see https://example.test now")
        val links = a.getLinkAnnotations(0, a.length).map { it.item }
        assertTrue(links.any { it is LinkAnnotation.Url }, "bare URL must become a LinkAnnotation")
    }

    @Test
    fun explicitMarkdownLinkStillLinked() {
        val a = build("a [label](https://x.test) b")
        val links = a.getLinkAnnotations(0, a.length).map { it.item }
        assertTrue(links.any { it is LinkAnnotation.Url }, "explicit link must remain a LinkAnnotation")
    }

    private class Link(val start: Int, val end: Int, val url: String)

    private fun links(a: AnnotatedString): List<Link> =
        a.getLinkAnnotations(0, a.length).map { Link(it.start, it.end, (it.item as LinkAnnotation.Url).url) }

    @Test
    fun capitalisedSchemeIsLinkedWithLowercaseUrlAndUnchangedText() {
        val a = build("Https://example.test/")
        assertEquals("Https://example.test/", a.text)
        val l = links(a).single()
        assertEquals(0, l.start)
        assertEquals(a.length, l.end)
        assertEquals("https://example.test/", l.url)
    }

    @Test
    fun upperCaseSchemeWithQueryCoversWholeUrl() {
        val text = "go HTTPS://example.test/a?b=1 now"
        val a = build(text)
        assertEquals(text, a.text)
        val l = links(a).single()
        assertEquals("HTTPS://example.test/a?b=1", a.text.substring(l.start, l.end))
        assertEquals("https://example.test/a?b=1", l.url)
    }

    @Test
    fun capitalisedWwwIsLinkedWithHttpsPrepended() {
        val a = build("Www.example.test")
        assertEquals("Www.example.test", a.text)
        val l = links(a).single()
        assertEquals(0, l.start)
        assertEquals(a.length, l.end)
        assertEquals("https://www.example.test", l.url)
    }

    @Test
    fun lowercaseWwwGetsHttpsPrepended() {
        val l = links(build("www.example.test")).single()
        assertEquals("https://www.example.test", l.url)
    }

    @Test
    fun capitalisedUrlInInlineCodeIsNotLinked() {
        assertTrue(links(build("see `Https://x.test` ok")).isEmpty())
    }

    @Test
    fun capitalisedUrlInCodeBlockIsNotLinked() {
        assertTrue(links(build("```\nHttps://x.test\n```")).isEmpty())
    }

    @Test
    fun capitalisedUrlInMarkdownLinkKeepsExactlyOneLink() {
        assertEquals(1, links(build("[a](Https://x.test)")).size)
    }

    @Test
    fun capitalisedUrlInsideStrikethroughExcludesDelimiters() {
        val a = build("~~Https://x.test~~")
        assertEquals("Https://x.test", a.text)
        val l = links(a).single()
        assertEquals("Https://x.test", a.text.substring(l.start, l.end))
        assertEquals("https://x.test", l.url)
    }

    @Test
    fun capitalisedUrlInsideUnderscoreEmphasisExcludesDelimiters() {
        val a = build("_Https://x.test/a_")
        assertEquals("Https://x.test/a", a.text)
        val l = links(a).single()
        assertEquals("Https://x.test/a", a.text.substring(l.start, l.end))
        assertEquals("https://x.test/a", l.url)
    }

    @Test
    fun trailingPunctuationIsNotPartOfTheLink() {
        val a = build("Https://example.test/a.")
        val l = links(a).single()
        assertEquals("Https://example.test/a", a.text.substring(l.start, l.end))
    }

    @Test
    fun lowercaseUrlWithCommaKeepsFullSpan() {
        val text = "https://x.test/a,b;c!d"
        val l = links(build(text)).single()
        assertEquals(text, build(text).text.substring(l.start, l.end))
    }

    @Test
    fun lowercaseUrlWithNonAsciiPathKeepsFullSpan() {
        val text = "see https://de.wikipedia.org/wiki/Größe ok"
        val a = build(text)
        val l = links(a).single()
        assertEquals("https://de.wikipedia.org/wiki/Größe", a.text.substring(l.start, l.end))
    }

    @Test
    fun mentionAndCapitalisedUrlInOneMessageAreBothHandled() {
        val ctx = MentionContext(names = persistentMapOf("frodo.demo.rocks" to "Frodo"))
        val a = build("@frodo.demo.rocks Https://x.test/ @frodo.demo.rocks", ctx)
        assertEquals("@Frodo Https://x.test/ @Frodo", a.text)
        assertEquals("https://x.test/", links(a).single().url)
        val last = a.text.lastIndexOf("@Frodo")
        assertTrue(a.spanStyles.any { it.start == 0 && it.end == 6 })
        assertTrue(a.spanStyles.any { it.start == last && it.end == last + 6 })
    }

    @Test
    fun backslashAfterBareUrlDoesNotDuplicateText() {
        val text = "Https://x.test/a\\*b c"
        val a = build(text)
        assertEquals(1, Regex("x\\.test").findAll(a.text).count())
    }
}
