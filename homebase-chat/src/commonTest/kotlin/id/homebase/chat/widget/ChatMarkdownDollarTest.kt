package id.homebase.chat.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import id.homebase.api.util.markdownToPlainPreview
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ChatMarkdownDollarTest {

    private val style = TextStyle(fontSize = 16.sp)

    private val issueMessage =
        "I spent the \$100 limit already... Do I wait for 2h30m for reset, ir, do I go \$200 x20 🤣"

    private val inlineCases = listOf(
        issueMessage,
        "costs \$5 total",
        "run `echo \$HOME \$PATH` now",
        "pay \$\$ later \$\$ ok",
    )

    private fun inlineText(content: String): String {
        var result: String? = null
        runComposeUiTest {
            setContent { result = buildChatInlineAnnotatedString(content, style, Color.Black).text }
            waitForIdle()
        }
        return result!!
    }

    @Test
    fun inlineBubbleKeepsDollarText() {
        assertEquals(issueMessage, inlineText(issueMessage))
        assertEquals("costs \$5 total", inlineText("costs \$5 total"))
        assertContains(inlineText("run `echo \$HOME \$PATH` now"), "echo \$HOME \$PATH")
        assertEquals("pay \$\$ later \$\$ ok", inlineText("pay \$\$ later \$\$ ok"))
    }

    @Test
    fun blockBubbleKeepsDollarText() = runComposeUiTest {
        setContent { ChatMarkdown(content = "- costs \$5 and \$10\n- pay \$\$ later \$\$ ok", style = style) }
        waitForIdle()
        val rendered = onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .joinToString("\n") { it.text }
        assertContains(rendered, "costs \$5 and \$10")
        assertContains(rendered, "pay \$\$ later \$\$ ok")
    }

    @Test
    fun conversationListPreviewKeepsDollarText() {
        assertEquals(issueMessage, markdownToPlainPreview(issueMessage, maxCodePoints = 200))
        assertEquals("pay \$\$ later \$\$ ok", markdownToPlainPreview("pay \$\$ later \$\$ ok", maxCodePoints = 200))
    }

    @Test
    fun replyQuoteKeepsDollarText() {
        for (case in inlineCases) {
            assertEquals(
                case,
                resolveReplyContentText(
                    replyText = case,
                    contentLabelText = null,
                    hasThumbnail = false,
                    hasMedia = false,
                    mediaFallbackLabel = "",
                ),
            )
        }
    }
}
