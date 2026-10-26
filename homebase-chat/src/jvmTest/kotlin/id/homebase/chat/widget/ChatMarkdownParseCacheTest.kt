package id.homebase.chat.widget

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class ChatMarkdownParseCacheTest {

    private val blockBody = "# Title\n\n- one\n- two"

    private class Counting {
        var parses = 0
        val cache = ChatMarkdownParseCache(parse = { parses++; parseChatMarkdown(it) })
    }

    @Test
    fun blockMessageParsesOnceAcrossRestyleAndRecycle() = runComposeUiTest {
        val counting = Counting()
        var color by mutableStateOf(Color.Black)
        var size by mutableStateOf(16.sp)
        var shown by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalChatMarkdownParseCache provides counting.cache) {
                if (shown) {
                    ChatMarkdown(content = blockBody, color = color, style = TextStyle(fontSize = size))
                }
            }
        }
        waitForIdle()
        assertEquals(1, counting.parses)

        color = Color.Red
        size = 20.sp
        waitForIdle()
        assertEquals(1, counting.parses)

        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        assertEquals(1, counting.parses)
    }

    @Test
    fun bubbleLayoutDecisionAndInlineBodyShareOneParse() = runComposeUiTest {
        val counting = Counting()
        setContent {
            CompositionLocalProvider(LocalChatMarkdownParseCache provides counting.cache) {
                ChatMarkdown(content = "plain **bold** text")
                ChatMarkdown(content = "plain **bold** text")
            }
        }
        waitForIdle()
        assertEquals(1, counting.parses)
    }

    @Test
    fun differentContentParsesAgain() = runComposeUiTest {
        val counting = Counting()
        var body by mutableStateOf(blockBody)
        setContent {
            CompositionLocalProvider(LocalChatMarkdownParseCache provides counting.cache) {
                ChatMarkdown(content = body)
            }
        }
        waitForIdle()
        body = "# Other\n\n- a"
        waitForIdle()
        assertEquals(2, counting.parses)
    }

    @Test
    fun evictsLeastRecentlyUsedPastMaxEntries() {
        var parses = 0
        val cache = ChatMarkdownParseCache(maxEntries = 2, parse = { parses++; parseChatMarkdown(it) })
        val a = cache.get("a")
        cache.get("b")
        assertSame(a, cache.get("a"))
        cache.get("c")
        assertEquals(2, cache.size)
        assertEquals(3, parses)

        cache.get("a")
        assertEquals(3, parses)
        cache.get("b")
        assertEquals(4, parses)
    }

    @Test
    fun softBreaksReparseOnlyWhenPromoted() {
        val plain = parseChatMarkdown("one line")
        val multi = parseChatMarkdown("one\ntwo")
        assertEquals("one line", plain.text)
        assertEquals("one  \ntwo", multi.text)
    }
}
