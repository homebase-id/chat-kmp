package id.homebase.core.ui.screens.card

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardColourSchemeTest {

    private val schemes = CardDesign.all.flatMap { design -> CardDesignSpecs.of(design)!!.schemes.map { design to it } }

    private fun luminance(hex: String): Double {
        val argb = hexToArgb(hex)
        val rgb = listOf(16, 8, 0).map { (argb shr it and 0xFF) / 255.0 }
        val (r, g, b) = rgb.map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: String, b: String): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun everySchemeReadsAtWcagAaa() {
        for ((design, scheme) in schemes) {
            assertTrue(contrast(scheme.ink, scheme.ground) >= 7.0, "$design/${scheme.id} ink on ground")
            assertTrue(contrast(scheme.surfaceInk, scheme.surface) >= 7.0, "$design/${scheme.id} surfaceInk on surface")
            assertTrue(contrast(scheme.muted, scheme.ground) >= 4.5, "$design/${scheme.id} muted on ground")
        }
    }

    // Every ground a card could have saved before the palette changed.
    private val retiredGrounds = mapOf(
        CardDesign.POSTER to listOf("#0F1626", "#2A0E14", "#0E1E16", "#10215C", "#3A1708"),
        CardDesign.BOARD to listOf("#1E5B45", "#5B2A6E", "#9A3B26", "#23262B", "#DCEBFA", "#F7E7A6", "#F6D6D9"),
        CardDesign.COLLAGE to listOf("#DDE5D3", "#DCE6F0", "#F3DCDC", "#E6DFF0", "#C9A882", "#1F1C19", "#EDEDED"),
        CardDesign.DOSSIER to listOf("#0D1B2A", "#0F1A14", "#1C0F10", "#F4F1EA", "#E8DCC6"),
        CardDesign.LINKS to listOf("#DDF0E6", "#FBE3D6", "#E7E3F7", "#14213D", "#F8F2C8"),
    )

    @Test
    fun aCardSavedOnARetiredGroundKeepsItsColoursButIsNeverOffered() {
        for ((design, grounds) in retiredGrounds) {
            val spec = CardDesignSpecs.of(design)!!
            for (ground in grounds) {
                val legacy = assertNotNull(CardDesignSpecs.schemeByGround(ground), "$design $ground")
                assertTrue(legacy in spec.legacySchemes, "$design $ground")
                assertTrue(spec.schemes.none { it.ground == ground }, "$design $ground offered")
                val saved = CardOverrides(palette = legacy.applyTo(CardPalette(accent = legacy.accents.first().takeIf { CardOption.ACCENT in spec.options })))
                assertEquals(saved, saved.prunedFor(design), "$design $ground")
                assertEquals(saved, CardOverrides.fromJson(saved.toJson()).prunedFor(design), "$design $ground")
            }
        }
    }

    @Test
    fun groundsStayUniqueWithTheRetiredOnesCounted() {
        val all = CardDesign.all.flatMap { CardDesignSpecs.of(it)!!.let { spec -> spec.schemes + spec.legacySchemes } }.map { it.ground.uppercase() }
        assertEquals(all.size, all.toSet().size)
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
            assertEquals(argbToHex(CardDesign.baseArgb(design)), spec.schemes.first().ground, "$design first scheme is the built-in look")
        }
    }

    @Test
    fun aSchemeAndTheAccentAreSetAndClearedIndependently() {
        val sky = CardDesignSpecs.of(CardDesign.BOARD)!!.schemes.first()
        val accent = sky.accents[1]
        val both = CardOverrides().with(CardOption.COLOURS, sky.ground).with(CardOption.ACCENT, accent)
        assertEquals(sky.applyTo(CardPalette(accent = accent)), both.palette)
        assertEquals(sky.ground, both.valueOf(CardOption.COLOURS))

        assertEquals(sky.applyTo(null), both.with(CardOption.ACCENT, null).palette)
        assertEquals(CardPalette(accent = accent), both.with(CardOption.COLOURS, null, design = CardDesign.BOARD).palette)
        assertNull(both.with(CardOption.COLOURS, null).with(CardOption.ACCENT, null).palette)
    }

    @Test
    fun resettingToThePresetDropsAnAccentThePresetDoesNotOffer() {
        val dark = CardDesignSpecs.of(CardDesign.LINKS)!!.schemes.first { it.id == "dark" }
        val links = CardOverrides().with(CardOption.COLOURS, dark.ground).with(CardOption.ACCENT, "#7FD4E8")
        assertNull(links.with(CardOption.COLOURS, null, design = CardDesign.LINKS).palette)
    }

    @Test
    fun switchingDesignDropsAnAccentTheNewPresetDoesNotOffer() {
        val blush = CardDesignSpecs.of(CardDesign.BOARD)!!.schemes.first()
        val board = CardOverrides().with(CardOption.COLOURS, blush.ground).with(CardOption.ACCENT, "#9BE3B8")
        val links = board.switchedTo(CardDesign.LINKS)
        assertNull(links.palette)
        val legacy = CardOverrides(palette = CardPalette(accent = "#123456"))
        assertEquals("#123456", legacy.prunedFor(CardDesign.LINKS).palette?.accent)
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

    @Test
    fun everySchemeOffersItsOwnReadableAccents() {
        val hex = Regex("^#[0-9a-fA-F]{6}$")
        for ((design, scheme) in schemes) {
            assertTrue(scheme.accents.size in 4..6, "$design/${scheme.id}")
            assertEquals(scheme.accents.size, scheme.accents.map { it.uppercase() }.toSet().size, "$design/${scheme.id} duplicates")
            for (accent in scheme.accents) {
                assertTrue(hex.matches(accent), "$design/${scheme.id}: $accent")
                assertTrue(contrast(accent, scheme.ground) >= 3.0, "$design/${scheme.id} accent $accent on ground")
            }
        }
    }

    @Test
    fun theAccentsOnOfferFollowTheActiveScheme() {
        val spec = CardDesignSpecs.of(CardDesign.BOARD)!!
        val purple = spec.schemes.first { it.id == "purple" }
        assertEquals(purple.accents, CardDesignSpecs.accentsFor(CardDesign.BOARD, purple.ground))
        assertEquals(purple.accents, CardDesignSpecs.accentsFor(CardDesign.BOARD, purple.ground.lowercase()))
        assertEquals(spec.schemes.first().accents, CardDesignSpecs.accentsFor(CardDesign.BOARD, null))
    }

    @Test
    fun changingSchemeKeepsAnAccentTheNewSchemeOffersAndOtherwiseFallsBack() {
        val spec = CardDesignSpecs.of(CardDesign.BOARD)!!
        val forest = spec.schemes.first { it.id == "forest" }
        val graphite = spec.schemes.first { it.id == "graphite" }
        val shared = forest.accents.first { it in graphite.accents }
        val kept = CardOverrides().with(CardOption.COLOURS, forest.ground).with(CardOption.ACCENT, shared)
            .with(CardOption.COLOURS, graphite.ground)
        assertEquals(shared, kept.valueOf(CardOption.ACCENT))

        val onlyForest = forest.accents.first { it !in graphite.accents }
        val fell = CardOverrides().with(CardOption.COLOURS, forest.ground).with(CardOption.ACCENT, onlyForest)
            .with(CardOption.COLOURS, graphite.ground)
        assertEquals(graphite.accents.first(), fell.valueOf(CardOption.ACCENT))
        assertEquals(graphite.ground, fell.valueOf(CardOption.COLOURS))

        val preset = CardOverrides().with(CardOption.COLOURS, forest.ground).with(CardOption.COLOURS, graphite.ground)
        assertNull(preset.valueOf(CardOption.ACCENT))
    }

    @Test
    fun aSavedAccentOutsideEverySetStillSurvivesLoadingAndPruning() {
        val legacy = "#123ABC"
        val board = CardDesignSpecs.of(CardDesign.BOARD)!!.schemes.first()
        val saved = CardOverrides().with(CardOption.COLOURS, board.ground).with(CardOption.ACCENT, legacy)
        val read = CardOverrides.fromJson(saved.toJson()).prunedFor(CardDesign.BOARD)
        assertEquals(legacy, read.valueOf(CardOption.ACCENT))
    }
}
