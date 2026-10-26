package id.homebase.chat.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BubbleCodeHighlightTest {
    private val code = "fun main() {\n    val greeting = \"hello\" // say hi\n    println(greeting + 42)\n}"

    private val primary = Color(0xFF2C58C3)
    private val schemes: Map<String, ColorScheme> = mapOf(
        "light" to lightColorScheme(primary = primary, onPrimary = Color.White),
        "dark" to darkColorScheme(primary = primary, onPrimary = Color.White),
    )

    private fun sides(scheme: ColorScheme) = mapOf("sent" to scheme.onPrimary, "received" to scheme.onSurface)

    @Test
    fun kotlinFenceHasMultipleColoursAndKeepsText() {
        for ((name, scheme) in schemes) for ((side, content) in sides(scheme)) {
            val out = highlightCode(code, "kotlin", bubbleCodeStyle(scheme, content).theme)
            assertEquals(code, out.text)
            val colours = out.spanStyles.mapNotNull { it.item.color.takeIf { c -> c.alpha > 0f } }.distinct()
            assertTrue(colours.size >= 2, "expected >=2 colours, got $colours ($name/$side)")
        }
    }

    @Test
    fun everyTokenColourReachesContrastOnTheCodeBackground() {
        for ((name, scheme) in schemes) for ((side, content) in sides(scheme)) {
            val style = bubbleCodeStyle(scheme, content)
            val out = highlightCode(code, "kotlin", style.theme)
            val colours = out.spanStyles.map { it.item.color }.filter { it.alpha > 0f }
            assertTrue(colours.isNotEmpty())
            for (c in colours) {
                val ratio = contrastRatio(c, style.background)
                assertTrue(ratio >= MIN_CODE_CONTRAST, "contrast $ratio for $c on ${style.background} ($name/$side)")
            }
        }
    }

    @Test
    fun themeFollowsMaterialSchemeAndBubbleSide() {
        val themes = schemes.values.flatMap { s -> sides(s).values.map { bubbleCodeStyle(s, it).theme } }
        assertEquals(4, themes.map { it.code to it.keyword }.distinct().size)
        assertNotEquals(
            bubbleCodeStyle(schemes.getValue("light"), Color.White).theme.code,
            bubbleCodeStyle(schemes.getValue("light"), schemes.getValue("light").onSurface).theme.code,
        )
    }

    @Test
    fun untaggedFenceIsSingleStyle() {
        val theme = bubbleCodeStyle(schemes.getValue("dark"), Color.White).theme
        for (tag in listOf(null, "", "  ")) {
            val out = highlightCode(code, tag, theme)
            assertEquals(code, out.text)
            assertTrue(out.spanStyles.isEmpty())
        }
    }

    @Test
    fun unknownLanguageStaysPlain() {
        val out = highlightCode(code, "notalanguage", bubbleCodeStyle(schemes.getValue("light"), Color.Black).theme)
        assertEquals(code, out.text)
        assertTrue(out.spanStyles.isEmpty())
    }
}
