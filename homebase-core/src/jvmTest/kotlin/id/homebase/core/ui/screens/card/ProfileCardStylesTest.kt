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
                style.portrait?.let { assertTrue(it in CardDesignSpecs.PORTRAIT_SHAPES) }
                style.socials?.let { assertTrue(it in CardDesignSpecs.SOCIALS_STYLES) }
                style.blocks?.let { assertTrue(it in CardDesignSpecs.PRESENTATIONS) }
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
        val bare = cardStylesFor(spec.copy(options = spec.options - CardOption.PORTRAIT_SHAPE - CardOption.SOCIALS_STYLE - CardOption.BLOCK_STYLE))
        assertTrue(bare.all { it.portrait == null && it.socials == null && it.blocks == null })
    }

    @Test
    fun aDesignWithoutALayoutOptionGetsNoneOfIt() {
        for (design in designs) {
            val options = CardDesignSpecs.of(design)!!.options
            for (style in cardStylesFor(design)) {
                assertEquals(CardOption.PORTRAIT_SHAPE in options, style.portrait != null, "$design ${style.id} portrait")
                assertEquals(CardOption.SOCIALS_STYLE in options, style.socials != null, "$design ${style.id} socials")
                assertEquals(CardOption.BLOCK_STYLE in options, style.blocks != null, "$design ${style.id} blocks")
            }
        }
        assertTrue(cardStylesFor(CardDesign.POSTER).all { it.portrait == null })
        assertTrue(cardStylesFor(CardDesign.COLLAGE).all { it.blocks == null })
        assertTrue(cardStylesFor(CardDesign.LINKS).all { it.blocks == null })
    }

    @Test
    fun applyingAStyleWritesWhatTheManualPickersWrite() {
        for (design in designs) for (style in cardStylesFor(design)) {
            val applied = CardOverrides().withStyle(style, design)
            val presetOrder = CardDesignSpecs.of(design)!!.presetOrder
            // As the editor does: each pick is one edit, pruned for the design.
            val manual = listOf(
                CardOption.COLOURS to style.ground,
                CardOption.ACCENT to style.accent,
                CardOption.DISPLAY_FONT to style.display,
                CardOption.TEXT_FONT to style.text,
                CardOption.PORTRAIT_SHAPE to style.portrait,
                CardOption.SOCIALS_STYLE to style.socials,
                CardOption.BLOCK_STYLE to style.blocks,
            ).fold(CardOverrides()) { edited, (option, value) -> edited.with(option, value, presetOrder, design).prunedFor(design) }
            assertEquals(manual, applied, "$design ${style.id}")
            assertEquals(applied, applied.prunedFor(design))
            assertEquals(style.ground, applied.valueOf(CardOption.COLOURS))
            assertEquals(style.accent, applied.valueOf(CardOption.ACCENT))
            assertEquals(style.display, applied.valueOf(CardOption.DISPLAY_FONT))
            assertEquals(style.text, applied.valueOf(CardOption.TEXT_FONT))
            assertEquals(style.portrait, applied.valueOf(CardOption.PORTRAIT_SHAPE))
            assertEquals(style.socials, applied.valueOf(CardOption.SOCIALS_STYLE))
            assertEquals(style.blocks, applied.valueOf(CardOption.BLOCK_STYLE))
            assertTrue(style.matches(applied))
        }
    }

    @Test
    fun theAppliedStyleIsTheOnlyOneSelected() {
        for (design in designs) {
            val styles = cardStylesFor(design)
            for (style in styles) {
                val applied = CardOverrides().withStyle(style, design)
                assertEquals(listOf(style.id), styles.filter { it.matches(applied) }.map { it.id })
                assertNull(customStyle(styles, applied, design))
            }
            assertTrue(styles.none { it.matches(CardOverrides()) })
        }
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
        val presetOrder = CardDesignSpecs.of(CardDesign.BOARD)!!.presetOrder
        for ((option, values) in listOf(
            CardOption.PORTRAIT_SHAPE to CardDesignSpecs.PORTRAIT_SHAPES,
            CardOption.SOCIALS_STYLE to CardDesignSpecs.SOCIALS_STYLES,
            CardOption.BLOCK_STYLE to CardDesignSpecs.PRESENTATIONS,
        )) {
            val other = values.first { it != applied.valueOf(option) }
            assertFalse(style.matches(applied.with(option, other, presetOrder).prunedFor(CardDesign.BOARD)), "$option -> $other")
            assertFalse(style.matches(applied.with(option, null, presetOrder).prunedFor(CardDesign.BOARD)), "$option -> preset")
        }
        assertTrue(style.matches(applied.withBlockOrder(presetOrder.reversed()).prunedFor(CardDesign.BOARD)))
    }

    @Test
    fun aCustomTileCarriesTheCardsLayout() {
        val styles = cardStylesFor(CardDesign.BOARD)
        val edited = CardOverrides().withStyle(styles.first(), CardDesign.BOARD).with(CardOption.PORTRAIT_SHAPE, "ellipse")
        val custom = customStyle(styles, edited, CardDesign.BOARD)!!
        assertEquals("ellipse", custom.portrait)
        assertEquals(styles.first().socials, custom.socials)
        assertEquals(styles.first().blocks, custom.blocks)
    }

    @Test
    fun manualOptionsStillWorkAfterAStyle() {
        val style = cardStylesFor(CardDesign.BOARD).first()
        val applied = CardOverrides().withStyle(style, CardDesign.BOARD).with(CardOption.DISPLAY_FONT, "caveat")
        assertEquals("caveat", applied.valueOf(CardOption.DISPLAY_FONT))
        assertEquals(style.ground, applied.valueOf(CardOption.COLOURS))
    }
}
