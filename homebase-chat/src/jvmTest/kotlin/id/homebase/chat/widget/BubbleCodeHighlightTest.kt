package id.homebase.chat.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import id.homebase.core.ui.theme.DarkColors
import id.homebase.core.ui.theme.LightColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BubbleCodeHighlightTest {
    private val code = "fun main() {\n    val greeting = \"hello\" // say hi\n    println(greeting + 42)\n}"

    private class Case(val scheme: ColorScheme, val sentBubble: Color, val sentContent: Color)

    private val cases: Map<String, Case> = mapOf(
        "light" to Case(
            lightColorScheme(
                primary = LightColors.Primary, onPrimary = LightColors.OnPrimary,
                secondary = LightColors.Secondary, onSurface = LightColors.OnSurface,
                surfaceContainerHigh = LightColors.Surface3,
            ),
            LightColors.Primary, LightColors.OnPrimary,
        ),
        "dark" to Case(
            darkColorScheme(
                primary = DarkColors.Primary, onPrimary = DarkColors.OnPrimary,
                secondary = DarkColors.Secondary, onSurface = DarkColors.OnSurface,
                surfaceContainerHigh = DarkColors.Surface3,
            ),
            LightColors.Primary, LightColors.OnPrimary,
        ),
    )

    private class Side(val content: Color, val bubble: Color, val sent: Boolean)

    private fun sides(c: Case) = mapOf(
        "sent" to Side(c.sentContent, c.sentBubble, true),
        "received" to Side(c.scheme.onSurface, c.scheme.surfaceContainerHigh, false),
    )

    private fun style(c: Case, s: Side) = bubbleCodeStyle(c.scheme, s.content, s.bubble, s.sent)

    @Test
    fun kotlinFenceHasMultipleColoursAndKeepsText() {
        for ((name, c) in cases) for ((side, sd) in sides(c)) {
            val out = highlightCode(code, "kotlin", style(c, sd).theme)
            assertEquals(code, out.text)
            val colours = out.spanStyles.mapNotNull { it.item.color.takeIf { c -> c.alpha > 0f } }.distinct()
            assertTrue(colours.size >= 2, "expected >=2 colours, got $colours ($name/$side)")
        }
    }

    @Test
    fun everyTokenColourReachesContrastOnTheCodeBackground() {
        for ((name, c) in cases) for ((side, sd) in sides(c)) {
            val style = style(c, sd)
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
        val themes = cases.values.flatMap { c -> sides(c).values.map { style(c, it).theme } }
        assertEquals(4, themes.map { it.code to it.keyword }.distinct().size)
        val light = cases.getValue("light")
        val s = sides(light)
        assertNotEquals(style(light, s.getValue("sent")).theme.code, style(light, s.getValue("received")).theme.code)
    }

    @Test
    fun untaggedFenceIsSingleStyle() {
        val dark = cases.getValue("dark")
        val theme = style(dark, sides(dark).getValue("sent")).theme
        for (tag in listOf(null, "", "  ")) {
            val out = highlightCode(code, tag, theme)
            assertEquals(code, out.text)
            assertTrue(out.spanStyles.isEmpty())
        }
    }

    @Test
    fun unknownLanguageStaysPlain() {
        val out = highlightCode(code, "notalanguage", style(cases.getValue("light"), sides(cases.getValue("light")).getValue("received")).theme)
        assertEquals(code, out.text)
        assertTrue(out.spanStyles.isEmpty())
    }
}
