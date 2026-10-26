package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BubbleCodeHighlightTest {
    private val code = "fun main() {\n    val greeting = \"hello\" // say hi\n    println(greeting + 42)\n}"

    @Test
    fun kotlinFenceHasMultipleColoursAndKeepsText() {
        for (dark in listOf(true, false)) {
            val out = highlightCode(code, "kotlin", dark)
            assertEquals(code, out.text)
            val colours = out.spanStyles.mapNotNull { it.item.color.takeIf { c -> c.alpha > 0f } }.distinct()
            assertTrue(colours.size >= 2, "expected >=2 colours, got $colours (dark=$dark)")
        }
    }

    @Test
    fun untaggedFenceIsSingleStyle() {
        for (tag in listOf(null, "", "  ")) {
            val out = highlightCode(code, tag, darkTheme = true)
            assertEquals(code, out.text)
            assertTrue(out.spanStyles.isEmpty())
        }
    }

    @Test
    fun unknownLanguageStaysPlain() {
        val out = highlightCode(code, "notalanguage", darkTheme = false)
        assertEquals(code, out.text)
        assertTrue(out.spanStyles.isEmpty())
    }
}
