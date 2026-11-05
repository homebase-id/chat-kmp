package id.homebase.core.ui.screens.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileCardStylesTest {

    private val designs = listOf(CardDesign.POSTER, CardDesign.BOARD, CardDesign.COLLAGE, CardDesign.DOSSIER, CardDesign.LINKS)

    @Test
    fun everyDesignHasOneStylePerOwnScheme() {
        for (design in designs) {
            val schemes = CardDesignSpecs.of(design)!!.schemes.map { it.id }
            assertEquals(schemes, cardStylesFor(design).map { it.id }, design)
        }
    }

    @Test
    fun everyDesignOffersAtLeastFiveStylesBuiltFromItsOwnValues() {
        for (design in designs) {
            val spec = CardDesignSpecs.of(design)!!
            val styles = cardStylesFor(design)
            assertTrue(styles.size >= 5, "$design offers ${styles.size}")
            for (style in styles) {
                val scheme = spec.schemes.single { it.ground == style.ground }
                assertEquals(scheme.ink, style.ink)
                style.accent?.let { assertTrue(it in scheme.accents) }
                style.display?.let { assertTrue(it in CardDesignSpecs.FONTS) }
                style.text?.let { assertTrue(it in CardDesignSpecs.FONTS) }
            }
        }
    }

    @Test
    fun posterOnlyGetsItsDarkGrounds() {
        val poster = CardDesignSpecs.of(CardDesign.POSTER)!!.schemes.map { it.ground }
        assertTrue(cardStylesFor(CardDesign.POSTER).all { it.ground in poster })
        assertTrue(cardStylesFor(CardDesign.POSTER).all { it.accent == null })
    }

    @Test
    fun aStyleThatHasNoSchemeInTheDesignIsHidden() {
        val board = CardDesignSpecs.of(CardDesign.BOARD)!!
        val withoutForest = board.copy(schemes = board.schemes.filter { it.id != "forest" })
        assertTrue(cardStylesFor(withoutForest).none { it.id == "forest" })
        assertTrue(cardStylesFor("zine").isEmpty())
    }

    @Test
    fun optionsTheDesignLacksAreLeftOutOfTheStyle() {
        val spec = CardDesignSpecs.of(CardDesign.BOARD)!!
        val trimmed = spec.copy(options = spec.options - CardOption.ACCENT - CardOption.TEXT_FONT)
        val style = cardStylesFor(trimmed).first()
        assertNull(style.accent)
        assertNull(style.text)
        assertTrue(style.display != null)
        assertTrue(cardStylesFor(spec.copy(options = spec.options - CardOption.COLOURS)).isEmpty())
    }

    @Test
    fun applyingAStyleWritesWhatTheManualPickersWrite() {
        for (design in designs) for (style in cardStylesFor(design)) {
            val applied = CardOverrides().withStyle(style, design)
            val manual = CardOverrides()
                .with(CardOption.COLOURS, style.ground, design = design)
                .with(CardOption.ACCENT, style.accent)
                .with(CardOption.DISPLAY_FONT, style.display)
                .with(CardOption.TEXT_FONT, style.text)
            assertEquals(manual, applied)
            assertEquals(applied, applied.prunedFor(design))
            assertEquals(style.ground, applied.valueOf(CardOption.COLOURS))
            assertEquals(style.accent, applied.valueOf(CardOption.ACCENT))
            assertEquals(style.display, applied.valueOf(CardOption.DISPLAY_FONT))
            assertEquals(style.text, applied.valueOf(CardOption.TEXT_FONT))
        }
    }

    @Test
    fun theAppliedStyleIsTheOnlyOneSelected() {
        val styles = cardStylesFor(CardDesign.BOARD)
        for (style in styles) {
            val applied = CardOverrides().withStyle(style, CardDesign.BOARD)
            assertEquals(listOf(style.id), styles.filter { it.matches(applied) }.map { it.id })
        }
        assertTrue(styles.none { it.matches(CardOverrides()) })
    }

    @Test
    fun changingAnyManualOptionDeselectsTheStyle() {
        val style = cardStylesFor(CardDesign.BOARD).first()
        val applied = CardOverrides().withStyle(style, CardDesign.BOARD)
        val other = CardDesignSpecs.FONTS.first { it != style.display }
        assertFalse(style.matches(applied.with(CardOption.DISPLAY_FONT, other)))
        assertFalse(style.matches(applied.with(CardOption.TEXT_FONT, null)))
        assertFalse(style.matches(applied.with(CardOption.ACCENT, null)))
        assertFalse(style.matches(applied.with(CardOption.COLOURS, null)))
        assertTrue(style.matches(applied.with(CardOption.SOCIALS_STYLE, "bar")))
    }

    @Test
    fun manualOptionsStillWorkAfterAStyle() {
        val style = cardStylesFor(CardDesign.BOARD).first()
        val applied = CardOverrides().withStyle(style, CardDesign.BOARD).with(CardOption.DISPLAY_FONT, "caveat")
        assertEquals("caveat", applied.valueOf(CardOption.DISPLAY_FONT))
        assertEquals(style.ground, applied.valueOf(CardOption.COLOURS))
    }
}
