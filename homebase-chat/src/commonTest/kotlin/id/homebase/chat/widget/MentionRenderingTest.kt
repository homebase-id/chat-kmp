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
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * How an `@mention` is decorated by [ChatMarkdown]'s two markdown shapes — the inline single-Text
 * path and the mikepenz block path — and, just as importantly, where it must NOT be: a code span,
 * a fenced block, an email address, a link label.
 */
@OptIn(ExperimentalTestApi::class)
class MentionRenderingTest {

    private class Rendered(
        val text: AnnotatedString,
        val mentionStyle: SpanStyle,
        val selfMentionStyle: SpanStyle,
    ) {
        fun mentionRuns(): List<String> = runsOf(mentionStyle)
        fun selfRuns(): List<String> = runsOf(selfMentionStyle)

        private fun runsOf(style: SpanStyle): List<String> = text.spanStyles
            .filter { it.item == style }
            .map { text.text.substring(it.start, it.end) }
    }

    private val self = "me.example.test"

    /** Builds the inline path's annotated string for [content] plus the styles a mention may carry. */
    private fun ComposeUiTest.inline(content: String, mentions: MentionContext? = null): Rendered {
        var rendered: Rendered? = null
        setContent {
            val style = MaterialTheme.typography.bodyLarge
            val color = LocalContentColor.current
            rendered = Rendered(
                text = buildChatInlineAnnotatedString(content, style, color, mentions),
                mentionStyle = mentionSpanStyle(style, color),
                selfMentionStyle = selfMentionSpanStyle(
                    style = style,
                    color = color,
                    selfBackground = MaterialTheme.colorScheme.tertiaryContainer,
                    selfContent = MaterialTheme.colorScheme.onTertiaryContainer,
                    sentBubble = mentions?.sentBubble == true,
                ),
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

    // --- #1425: a mention of YOU, and the names a chip draws.

    /** No context is the feed's shape: general chip, raw odinId, nothing self-highlighted. */
    @Test
    fun withoutAContextEveryMentionIsTheGeneralChip() = runComposeUiTest {
        val rendered = inline("hey @me.example.test and @alice.example.test")
        assertEquals(
            listOf("@me.example.test", "@alice.example.test"),
            rendered.mentionRuns(),
        )
        assertEquals(emptyList(), rendered.selfRuns())
        assertEquals("hey @me.example.test and @alice.example.test", rendered.text.text)
    }

    @Test
    fun aMentionOfMeCarriesTheStrongerStyle() = runComposeUiTest {
        val rendered = inline(
            "hey @me.example.test and @alice.example.test",
            MentionContext(selfOdinId = self),
        )
        assertEquals(listOf("@me.example.test"), rendered.selfRuns())
        assertEquals(listOf("@alice.example.test"), rendered.mentionRuns())
        assertNotEquals(rendered.mentionStyle, rendered.selfMentionStyle)
    }

    /** `@me.example.test/inbox` names me — the decorated range reaches past the identity. */
    @Test
    fun aMentionOfMeIsFoundThroughAPathSuffix() = runComposeUiTest {
        val rendered = inline("hi @me.example.test/inbox", MentionContext(selfOdinId = self))
        assertEquals(listOf("@me.example.test/inbox"), rendered.selfRuns())
    }

    /** An identity my odinId is merely a prefix of is somebody else. */
    @Test
    fun aLongerIdentityIsNotMe() = runComposeUiTest {
        val rendered = inline("hi @me.example.test.evil.test", MentionContext(selfOdinId = self))
        assertEquals(emptyList(), rendered.selfRuns())
        assertEquals(listOf("@me.example.test.evil.test"), rendered.mentionRuns())
    }

    @Test
    fun aKnownMentionDrawsTheContactName() = runComposeUiTest {
        val rendered = inline(
            "hey @alice.example.test how are you",
            MentionContext(names = persistentMapOf("alice.example.test" to "Alice Smith")),
        )
        assertEquals(listOf("@Alice Smith"), rendered.mentionRuns())
        assertEquals("hey @Alice Smith how are you", rendered.text.text)
    }

    /** Only the identity is swapped, so a token that reaches past it keeps its tail. */
    @Test
    fun aSubstitutedNameKeepsThePathSuffix() = runComposeUiTest {
        val rendered = inline(
            "hi @alice.example.test/inbox",
            MentionContext(names = persistentMapOf("alice.example.test" to "Alice Smith")),
        )
        assertEquals(listOf("@Alice Smith/inbox"), rendered.mentionRuns())
    }

    /** No contact, no name: the raw odinId, never a blank chip. */
    @Test
    fun anUnknownMentionKeepsTheRawOdinId() = runComposeUiTest {
        val rendered = inline(
            "hey @bob.example.test",
            MentionContext(names = persistentMapOf("alice.example.test" to "Alice Smith")),
        )
        assertEquals(listOf("@bob.example.test"), rendered.mentionRuns())
    }

    /** The user's extension: a mention of me shows MY full name, not the handle and not "You". */
    @Test
    fun aMentionOfMeDrawsMyOwnName() = runComposeUiTest {
        val rendered = inline(
            "can you look at this @me.example.test?",
            MentionContext(
                selfOdinId = self,
                names = persistentMapOf(self to "Bishwajeet Parhi"),
            ),
        )
        assertEquals(listOf("@Bishwajeet Parhi"), rendered.selfRuns())
        assertEquals("can you look at this @Bishwajeet Parhi?", rendered.text.text)
    }

    /**
     * Self-detection runs on the RAW body, before any name resolution — so a contact who happens
     * to carry my display name is still styled as someone else.
     */
    @Test
    fun selfDetectionReadsTheOdinIdNotTheResolvedName() = runComposeUiTest {
        val rendered = inline(
            "hey @alice.example.test",
            MentionContext(
                selfOdinId = self,
                names = persistentMapOf("alice.example.test" to "Bishwajeet Parhi"),
            ),
        )
        assertEquals(emptyList(), rendered.selfRuns())
        assertEquals(listOf("@Bishwajeet Parhi"), rendered.mentionRuns())
    }

    /**
     * The degenerate case — you mentioning yourself in your own message. The sent bubble is
     * painted `primary`, so the accent hue is dropped for a denser bubble-relative tint; it must
     * still be distinguishable from the general chip.
     */
    @Test
    fun onASentBubbleTheSelfChipFallsBackToABubbleRelativeTint() = runComposeUiTest {
        val rendered = inline(
            "note to @me.example.test",
            MentionContext(selfOdinId = self, sentBubble = true),
        )
        assertEquals(listOf("@me.example.test"), rendered.selfRuns())
        assertNotEquals(rendered.mentionStyle, rendered.selfMentionStyle)
        assertEquals(rendered.mentionStyle.color, rendered.selfMentionStyle.color)
    }

    /** ...whereas a received bubble takes a real accent role, hue and all. */
    @Test
    fun onAReceivedBubbleTheSelfChipTakesAnAccentRole() = runComposeUiTest {
        val rendered = inline(
            "ping @me.example.test",
            MentionContext(selfOdinId = self, sentBubble = false),
        )
        assertEquals(listOf("@me.example.test"), rendered.selfRuns())
        assertNotEquals(rendered.mentionStyle.color, rendered.selfMentionStyle.color)
    }

    @Test
    fun aCodeSpanIsStillNotChippedWhenItNamesMe() = runComposeUiTest {
        val rendered = inline(
            "run ` @me.example.test ` verbatim",
            MentionContext(selfOdinId = self, names = persistentMapOf(self to "Bishwajeet Parhi")),
        )
        assertEquals(emptyList(), rendered.selfRuns())
        assertEquals(emptyList(), rendered.mentionRuns())
        assertTrue(rendered.text.text.contains("@me.example.test"))
    }

    /** The block path publishes the same annotator, so the self chip and the name land there too. */
    @Test
    fun blockPathCarriesTheSelfChipAndTheResolvedName() = runComposeUiTest {
        var selfStyle: SpanStyle? = null
        setContent {
            val style = MaterialTheme.typography.bodyLarge
            val color = LocalContentColor.current
            selfStyle = selfMentionSpanStyle(
                style = style,
                color = color,
                selfBackground = MaterialTheme.colorScheme.tertiaryContainer,
                selfContent = MaterialTheme.colorScheme.onTertiaryContainer,
                sentBubble = false,
            )
            Box(Modifier.testTag("md")) {
                ChatMarkdown(
                    content = "- hi @me.example.test\n- second",
                    style = style,
                    color = color,
                    mentions = MentionContext(
                        selfOdinId = self,
                        names = persistentMapOf(self to "Bishwajeet Parhi"),
                    ),
                )
            }
        }
        val drawn = drawnText("@Bishwajeet Parhi")
        val runs = drawn.spanStyles
            .filter { it.item == requireNotNull(selfStyle) }
            .map { drawn.text.substring(it.start, it.end) }
        assertEquals(listOf("@Bishwajeet Parhi"), runs)
    }
}
