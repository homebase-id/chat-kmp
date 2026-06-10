package id.homebase.chat.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatInlineAnnotatedStringTest {

    private val style = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Default)

    private fun build(content: String): AnnotatedString {
        var result: AnnotatedString? = null
        // Capture inside a composition so the test works whether or not the
        // builder is @Composable. The builder applies withChatHardLineBreaks itself.
        runComposeUiTest {
            setContent { result = buildChatInlineAnnotatedString(content, style, Color.Black) }
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
}
