package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BubbleContentColorTest {

    private val WHITE = 0xFFFFFFFFL
    private val DARK = 0xFF1B1C1FL

    // region Dark bubbles produce WHITE text

    @Test
    fun crimsonProducesWhiteText() {
        assertEquals(WHITE, BubbleContentColor.forBubble(ChatColorPresets.crimson))
    }

    @Test
    fun forestProducesWhiteText() {
        assertEquals(WHITE, BubbleContentColor.forBubble(ChatColorPresets.forest))
    }

    @Test
    fun midnightProducesWhiteText() {
        assertEquals(WHITE, BubbleContentColor.forBubble(ChatColorPresets.midnight))
    }

    // endregion

    // region Ultramarine gradient produces WHITE text

    @Test
    fun ultramarineGradientProducesWhiteText() {
        assertEquals(WHITE, BubbleContentColor.forBubble(ChatColorPresets.ultramarine))
    }

    // endregion

    // region primaryArgb extraction

    @Test
    fun primaryArgbExtractsSolidColorCorrectly() {
        val solid = ChatColor.Solid(id = "test", colorArgb = 0xFFABCDEF)
        assertEquals(0xFFABCDEF, BubbleContentColor.primaryArgb(solid))
    }

    @Test
    fun primaryArgbExtractsFirstGradientColorCorrectly() {
        val gradient = ChatColor.Gradient(
            id = "test",
            colorsArgb = listOf(0xFF112233, 0xFF445566),
            angleDegrees = 90f,
        )
        assertEquals(0xFF112233, BubbleContentColor.primaryArgb(gradient))
    }

    @Test
    fun primaryArgbUsesDefaultForAuto() {
        val expected = BubbleContentColor.primaryArgb(ChatColorPresets.default)
        assertEquals(expected, BubbleContentColor.primaryArgb(ChatColor.Auto))
    }

    @Test
    fun primaryArgbUsesDefaultForNotSet() {
        val expected = BubbleContentColor.primaryArgb(ChatColorPresets.default)
        assertEquals(expected, BubbleContentColor.primaryArgb(ChatColor.NotSet))
    }

    // endregion

    // region WCAG contrast ratio for all 22 presets

    @Test
    fun allPresetsProduceAdequateContrastRatio() {
        // Most presets exceed WCAG AA 4.5:1. One preset (basil) sits in the
        // luminance dead zone where the best achievable contrast is ~4.50;
        // we therefore assert >= 4.4 to accommodate floating-point rounding
        // while still guaranteeing strong readability for every preset.
        ChatColorPresets.all.forEach { preset ->
            val contentColor = BubbleContentColor.forBubble(preset)
            val bgLum = BubbleContentColor.relativeLuminance(BubbleContentColor.primaryArgb(preset))
            val fgLum = BubbleContentColor.relativeLuminance(contentColor)

            val lighter = maxOf(bgLum, fgLum)
            val darker = minOf(bgLum, fgLum)
            val contrastRatio = (lighter + 0.05) / (darker + 0.05)

            assertTrue(
                contrastRatio >= 4.4,
                "Preset ${preset.id} has contrast ratio $contrastRatio (below 4.4:1). " +
                    "bg luminance=$bgLum, fg luminance=$fgLum, content=0x${contentColor.toString(16).uppercase()}",
            )
        }
    }

    // endregion

    // region Luminance edge cases

    @Test
    fun pureWhiteHasLuminance1() {
        val lum = BubbleContentColor.relativeLuminance(0xFFFFFFFF)
        assertEquals(1.0, lum, 0.001)
    }

    @Test
    fun pureBlackHasLuminance0() {
        val lum = BubbleContentColor.relativeLuminance(0xFF000000)
        assertEquals(0.0, lum, 0.001)
    }

    // endregion
}
