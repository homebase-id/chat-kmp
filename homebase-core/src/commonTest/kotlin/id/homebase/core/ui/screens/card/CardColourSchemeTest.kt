package id.homebase.core.ui.screens.card

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardColourSchemeTest {

    private val schemes = CardDesign.all.flatMap { design -> CardDesignSpecs.of(design)!!.schemes.map { design to it } }

    private fun luminance(hex: String): Double {
        val rgb = hex.removePrefix("#").chunked(2).map { it.toInt(16) / 255.0 }
        val (r, g, b) = rgb.map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: String, b: String): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun everySchemeReadsAtWcagAa() {
        for ((design, scheme) in schemes) {
            assertTrue(contrast(scheme.ink, scheme.ground) >= 4.5, "$design/${scheme.id} ink on ground")
            assertTrue(contrast(scheme.surfaceInk, scheme.surface) >= 4.5, "$design/${scheme.id} surfaceInk on surface")
            assertTrue(contrast(scheme.muted, scheme.ground) >= 3.0, "$design/${scheme.id} muted on ground")
        }
    }

    @Test
    fun schemesAreWhatTheWebAcceptsAndFindableByGround() {
        val hex = Regex("^#[0-9a-fA-F]{6}$")
        for ((design, scheme) in schemes) {
            listOf(scheme.ground, scheme.ink, scheme.muted, scheme.surface, scheme.surfaceInk).forEach {
                assertTrue(hex.matches(it), "$design/${scheme.id}: $it")
            }
            assertEquals(scheme, CardDesignSpecs.schemeByGround(scheme.ground.lowercase()))
        }
        assertEquals(schemes.size, schemes.map { it.second.ground.uppercase() }.toSet().size)
        for (design in CardDesign.all) {
            val spec = CardDesignSpecs.of(design)!!
            assertEquals(CardOption.COLOURS in spec.options, spec.schemes.isNotEmpty(), design)
            assertTrue(spec.schemes.size in 5..7, design)
        }
    }

    @Test
    fun aSchemeAndTheAccentAreSetAndClearedIndependently() {
        val forest = CardDesignSpecs.of(CardDesign.BOARD)!!.schemes.first()
        val both = CardOverrides().with(CardOption.COLOURS, forest.ground).with(CardOption.ACCENT, "#F2B84B")
        assertEquals(forest.applyTo(CardPalette(accent = "#F2B84B")), both.palette)
        assertEquals(forest.ground, both.valueOf(CardOption.COLOURS))

        assertEquals(forest.applyTo(null), both.with(CardOption.ACCENT, null).palette)
        assertEquals(CardPalette(accent = "#F2B84B"), both.with(CardOption.COLOURS, null).palette)
        assertNull(both.with(CardOption.COLOURS, null).with(CardOption.ACCENT, null).palette)
    }

    @Test
    fun aSavedSchemeRoundTripsThroughTheStoredJson() {
        val paper = CardDesignSpecs.of(CardDesign.DOSSIER)!!.schemes.first { it.id == "paper" }
        val saved = CardOverrides().with(CardOption.COLOURS, paper.ground).with(CardOption.ACCENT, "#8B7CF6")
        val read = CardOverrides.fromJson(saved.toJson())
        assertEquals(saved, read)
        assertEquals(paper.ground, read.prunedFor(CardDesign.DOSSIER).valueOf(CardOption.COLOURS))
    }

    @Test
    fun aDesignKeepsOnlyItsOwnSchemes() {
        val forest = CardDesignSpecs.of(CardDesign.BOARD)!!.schemes.first()
        val board = CardOverrides().with(CardOption.COLOURS, forest.ground).with(CardOption.ACCENT, "#F2B84B")
        assertEquals(CardPalette(accent = "#F2B84B"), board.prunedFor(CardDesign.DOSSIER).palette)
        assertNull(board.prunedFor(CardDesign.POSTER).palette)
        assertEquals(board, board.prunedFor(CardDesign.BOARD))

        val unchecked = CardOverrides(palette = CardPalette(ground = "#123456", ink = "#FFFFFF", accent = "#F2B84B"))
        assertEquals(CardPalette(accent = "#F2B84B"), unchecked.prunedFor(CardDesign.COLLAGE).palette)
    }
}
