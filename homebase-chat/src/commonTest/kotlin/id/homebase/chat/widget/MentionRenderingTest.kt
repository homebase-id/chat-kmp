package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How an `@mention` is decorated by [ChatMarkdown]'s two markdown shapes — the inline single-Text
 * path and the mikepenz block path — and, just as importantly, where it must NOT be: a code span,
 * a fenced block, an email address, a link label.
 */
@OptIn(ExperimentalTestApi::class)
class MentionRenderingTest {

    private class Rendered(val text: AnnotatedString, val mentionStyle: SpanStyle) {
        fun mentionRuns(): List<String> = text.spanStyles
            .filter { it.item == mentionStyle }
            .map { text.text.substring(it.start, it.end) }
    }

    /** Builds the inline path's annotated string for [content] plus the style a mention must carry. */
    private fun ComposeUiTest.inline(content: String): Rendered {
        var rendered: Rendered? = null
        setContent {
            val style = MaterialTheme.typography.bodyLarge
            val color = LocalContentColor.current
            rendered = Rendered(
                text = buildChatInlineAnnotatedString(content, style, color),
                mentionStyle = mentionSpanStyle(style, color),
            )
        }
        waitForIdle()
        return requireNotNull(rendered)
    }

    @Test
    fun inlineMentionCarriesTheMentionStyle() = runComposeUiTest {
        val rendered = inline("hey @alice.example.test how are you")
        assertEquals(listOf("@alice.example.test"), rendered.mentionRuns())
        assertEquals("hey @alice.example.test how are you", rendered.text.text)
    }

    @Test
    fun inlineSeveralMentionsAreEachStyled() = runComposeUiTest {
        val rendered = inline("@alice.example.test and @bob.example.test")
        assertEquals(listOf("@alice.example.test", "@bob.example.test"), rendered.mentionRuns())
    }

    /** The mention chip must survive an emoji pressed against it, whole and unsplit. */
    @Test
    fun inlineMentionSurvivesAdjacentEmoji() = runComposeUiTest {
        val rendered = inline("😀 @alice.example.test 😀")
        assertEquals(listOf("@alice.example.test"), rendered.mentionRuns())
        assertEquals("😀 @alice.example.test 😀", rendered.text.text)
    }

    /** A code span is a literal the author asked us to show verbatim. */
    @Test
    fun inlineCodeSpanIsNotLinkified() = runComposeUiTest {
        val rendered = inline("run `@alice.example.test` verbatim")
        assertEquals(emptyList(), rendered.mentionRuns())
        assertTrue(rendered.text.text.contains("@alice.example.test"))
    }

    @Test
    fun emailAddressIsNotLinkified() = runComposeUiTest {
        val rendered = inline("write to alice@example.test today")
        assertEquals(emptyList(), rendered.mentionRuns())
    }

    /** A link label is already spoken for by its own link annotation. */
    @Test
    fun linkLabelIsNotLinkified() = runComposeUiTest {
        val rendered = inline("[@alice.example.test](https://example.test)")
        assertEquals(emptyList(), rendered.mentionRuns())
        val links = rendered.text.getLinkAnnotations(0, rendered.text.length)
        assertTrue(links.any { (it.item as? LinkAnnotation.Url)?.url == "https://example.test" })
    }

    /**
     * Emphasis markers are not whitespace, so `**@alice…**` is not a mention. Web agrees — it tests
     * the same raw body string, and renders it without markdown at all.
     */
    @Test
    fun emphasisMarkerBeforeTheAtIsNotAMention() = runComposeUiTest {
        val rendered = inline("**@alice.example.test**")
        assertEquals(emptyList(), rendered.mentionRuns())
    }

    @Test
    fun bodyWithNoMentionIsUnchanged() = runComposeUiTest {
        val rendered = inline("plain **body** with `code` and no handles")
        assertEquals(emptyList(), rendered.mentionRuns())
    }

    /**
     * Reads back the annotated string a rendered node actually drew. Unmerged, because a list item
     * merges its bullet and its body into one node and the bullet would come back first.
     */
    private fun ComposeUiTest.drawnText(needle: String): AnnotatedString {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(needle, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        return onAllNodesWithText(needle, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .first { it.text.contains(needle) }
    }

    /**
     * A fenced code block reaches the screen through mikepenz's code components, which lift the raw
     * text and never consult an annotator — so its contents carry no spans at all.
     */
    @Test
    fun fencedCodeBlockIsNotLinkified() = runComposeUiTest {
        var mentionStyle: SpanStyle? = null
        setContent {
            val style = MaterialTheme.typography.bodyLarge
            val color = LocalContentColor.current
            mentionStyle = mentionSpanStyle(style, color)
            Box(Modifier.testTag("md")) {
                ChatMarkdown(
                    content = "ping @bob.example.test\n\n```\n@alice.example.test\n```",
                    style = style,
                    color = color,
                )
            }
        }
        assertEquals(emptyList(), drawnText("@alice.example.test").spanStyles)
        // The prose mention in the same body IS chipped, so an inert annotator cannot pass this.
        val prose = drawnText("@bob.example.test")
        assertTrue(prose.spanStyles.any { it.item == requireNotNull(mentionStyle) })
    }

    /**
     * The block path publishes the same annotator, so a mention inside a list item gets the same
     * chip the inline path draws — that is the whole point of routing both through one hook.
     */
    @Test
    fun blockPathMentionCarriesTheMentionStyle() = runComposeUiTest {
        var mentionStyle: SpanStyle? = null
        setContent {
            val style = MaterialTheme.typography.bodyLarge
            val color = LocalContentColor.current
            mentionStyle = mentionSpanStyle(style, color)
            Box(Modifier.testTag("md")) {
                ChatMarkdown(content = "- hi @alice.example.test\n- second", style = style, color = color)
            }
        }
        val drawn = drawnText("@alice.example.test")
        val runs = drawn.spanStyles
            .filter { it.item == requireNotNull(mentionStyle) }
            .map { drawn.text.substring(it.start, it.end) }
        assertEquals(listOf("@alice.example.test"), runs)
    }

    /**
     * A code span inside a block body is excluded exactly as it is on the inline path — while the
     * prose mention beside it, in the same paragraph and the same annotated string, is chipped.
     */
    @Test
    fun blockPathCodeSpanIsNotLinkified() = runComposeUiTest {
        var mentionStyle: SpanStyle? = null
        setContent {
            val style = MaterialTheme.typography.bodyLarge
            val color = LocalContentColor.current
            mentionStyle = mentionSpanStyle(style, color)
            Box(Modifier.testTag("md")) {
                ChatMarkdown(
                    content = "# Heading\n\nrun `@alice.example.test` but ping @bob.example.test",
                    style = style,
                    color = color,
                )
            }
        }
        val drawn = drawnText("@alice.example.test")
        val runs = drawn.spanStyles
            .filter { it.item == requireNotNull(mentionStyle) }
            .map { drawn.text.substring(it.start, it.end) }
        assertEquals(listOf("@bob.example.test"), runs)
    }
}
