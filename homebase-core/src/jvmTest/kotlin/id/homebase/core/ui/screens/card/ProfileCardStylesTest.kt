package id.homebase.core.ui.screens.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileCardStylesTest {

    private val designs = listOf(CardDesign.POSTER, CardDesign.BOARD, CardDesign.COLLAGE, CardDesign.DOSSIER, CardDesign.LINKS)

    private fun default(design: String) = cardStylesFor(design).first()

    // Everything a preset can touch, set to something other than the preset.
    private fun styled(design: String): CardOverrides {
        val spec = CardDesignSpecs.of(design)!!
        val presetOrder = spec.presetOrder
        return listOf(
            CardOption.COLOURS to spec.schemes[1].ground,
            CardOption.ACCENT to spec.schemes[1].accents.last(),
            CardOption.DISPLAY_FONT to "caveat",
            CardOption.TEXT_FONT to "space-mono",
            CardOption.PORTRAIT_SHAPE to "ellipse",
            CardOption.SOCIALS_STYLE to "handles",
            CardOption.BLOCK_STYLE to "button",
        ).fold(CardOverrides()) { edited, (option, value) -> edited.with(option, value, presetOrder, design).prunedFor(design) }
    }

    @Test
    fun everyDesignHasOneStylePerOwnSchemeAndTheFirstIsDefault() {
        for (design in designs) {
            val schemes = CardDesignSpecs.of(design)!!.schemes.map { it.id }
            val styles = cardStylesFor(design)
            assertEquals(schemes, styles.map { it.id }, design)
            assertEquals(listOf(true) + List(styles.size - 1) { false }, styles.map { it.isDefault }, design)
        }
    }

    @Test
    fun theDefaultTileIsTheBuiltInLookAndItsSchemeIsThatLookToo() {
        for (design in designs) {
            val scheme = CardDesignSpecs.of(design)!!.schemes.first()
            val style = default(design)
            assertEquals(argbToHex(CardDesign.baseArgb(design)), style.ground, design)
            assertEquals(argbToHex(CardDesign.inkArgb(design)), style.ink, design)
            assertEquals(scheme.ground, style.ground, design)
            assertEquals(scheme.ink, style.ink, design)
            assertNull(style.accent, design)
        }
    }

    @Test
    fun everyDesignOffersAtLeastFiveColourOnlyStylesBuiltFromItsOwnValues() {
        for (design in designs) {
            val spec = CardDesignSpecs.of(design)!!
            val styles = cardStylesFor(design)
            assertTrue(styles.size >= 5, "$design offers ${styles.size}")
            for (style in styles) {
                val scheme = spec.schemes.single { it.ground == style.ground }
                assertEquals(scheme.ink, style.ink)
                val accent = if (style.isDefault || CardOption.ACCENT !in spec.options) null else scheme.accents.first()
                assertEquals(accent, style.accent, "$design ${style.id}")
                assertTrue(listOf(style.display, style.text, style.portrait, style.socials, style.blocks).all { it == null }, "$design ${style.id}")
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
    fun aDesignWithoutColoursHasNoStyles() {
        val spec = CardDesignSpecs.of(CardDesign.BOARD)!!
        assertTrue(cardStylesFor(spec.copy(options = spec.options - CardOption.COLOURS)).isEmpty())
        assertTrue(cardStylesFor(spec.copy(options = spec.options - CardOption.ACCENT)).all { it.accent == null })
        assertTrue(cardStylesFor("zine").isEmpty())
    }

    @Test
    fun applyingAStyleWritesWhatTheManualPickersWrite() {
        for (design in designs) for (style in cardStylesFor(design).filterNot { it.isDefault }) {
            val applied = CardOverrides().withStyle(style, design)
            val manual = listOf(CardOption.COLOURS to style.ground, CardOption.ACCENT to style.accent)
                .fold(CardOverrides()) { edited, (option, value) -> edited.with(option, value, design = design).prunedFor(design) }
            assertEquals(manual, applied, "$design ${style.id}")
            assertEquals(applied, applied.prunedFor(design))
            assertEquals(style.ground, applied.valueOf(CardOption.COLOURS))
            assertEquals(style.accent, applied.valueOf(CardOption.ACCENT))
            assertTrue(style.matches(applied))
        }
    }

    @Test
    fun aColourStyleLeavesFontsAndLayoutAsTheyWere() {
        for (design in designs) {
            val before = styled(design)
            val style = cardStylesFor(design).last()
            val applied = before.withStyle(style, design)
            for (option in listOf(CardOption.DISPLAY_FONT, CardOption.TEXT_FONT, CardOption.PORTRAIT_SHAPE, CardOption.SOCIALS_STYLE, CardOption.BLOCK_STYLE)) {
                assertEquals(before.valueOf(option), applied.valueOf(option), "$design $option")
            }
            assertTrue(style.matches(applied), design)
        }
    }

    @Test
    fun defaultClearsEveryPresetOptionButKeepsTheSectionOrder() {
        for (design in designs) {
            val spec = CardDesignSpecs.of(design)!!
            val reordered = styled(design).withBlockOrder(spec.presetOrder.reversed()).prunedFor(design)
            val cleared = reordered.withStyle(default(design), design)
            assertNull(cleared.palette, design)
            assertNull(cleared.type, design)
            assertNull(cleared.portraits, design)
            assertNull(cleared.socials, design)
            assertTrue(cleared.blocks.orEmpty().all { it.presentation == null }, design)
            assertEquals(reordered.blockOrder(), cleared.blockOrder(), design)
            assertTrue(default(design).matches(cleared), design)
            assertEquals(CardOverrides.EMPTY, styled(design).withStyle(default(design), design), design)
        }
    }

    @Test
    fun aBareCardSelectsDefault() {
        for (design in designs) {
            val styles = cardStylesFor(design)
            assertEquals(listOf(styles.first().id), styles.filter { it.matches(CardOverrides()) }.map { it.id }, design)
            assertNull(customStyle(styles, CardOverrides(), design), design)
        }
    }

    @Test
    fun anUntouchedTemplatedCardSelectsDefault() {
        val audiences = listOf(CardAudience.Public) +
            listOf(FRIENDS_CIRCLE_ID, FAMILY_CIRCLE_ID, WORK_CIRCLE_ID).map { CardAudience.Circle(it, "") }
        for (audience in audiences) {
            val template = cardTemplateFor(audience)!!
            val styles = cardStylesFor(template.design)
            assertEquals(listOf(styles.first().id), styles.filter { it.matches(template.overrides) }.map { it.id }, "$audience")
            assertNull(customStyle(styles, template.overrides, template.design), "$audience")
        }
    }

    @Test
    fun theAppliedStyleIsTheOnlyOneSelected() {
        for (design in designs) {
            val styles = cardStylesFor(design)
            for (style in styles) {
                val applied = CardOverrides().withStyle(style, design)
                assertEquals(listOf(style.id), styles.filter { it.matches(applied) }.map { it.id }, "$design ${style.id}")
                assertNull(customStyle(styles, applied, design))
            }
        }
    }

    @Test
    fun anyPresetOptionDeselectsDefault() {
        val design = CardDesign.BOARD
        val style = default(design)
        val presetOrder = CardDesignSpecs.of(design)!!.presetOrder
        val second = CardDesignSpecs.of(design)!!.schemes[1]
        for ((option, value) in listOf(
            CardOption.COLOURS to second.ground,
            CardOption.ACCENT to second.accents.first(),
            CardOption.DISPLAY_FONT to "caveat",
            CardOption.TEXT_FONT to "caveat",
            CardOption.PORTRAIT_SHAPE to "ellipse",
            CardOption.SOCIALS_STYLE to "handles",
            CardOption.BLOCK_STYLE to "row",
        )) {
            assertFalse(style.matches(CardOverrides().with(option, value, presetOrder, design).prunedFor(design)), "$option -> $value")
        }
        assertTrue(style.matches(CardOverrides().withBlockOrder(presetOrder.reversed()).prunedFor(design)))
    }

    @Test
    fun changingTheColoursOrAccentDeselectsAColourStyle() {
        val style = cardStylesFor(CardDesign.BOARD)[1]
        val applied = CardOverrides().withStyle(style, CardDesign.BOARD)
        assertFalse(style.matches(applied.with(CardOption.ACCENT, null)))
        assertFalse(style.matches(applied.with(CardOption.ACCENT, CardDesignSpecs.of(CardDesign.BOARD)!!.schemes[1].accents.last())))
        assertFalse(style.matches(applied.with(CardOption.COLOURS, null, design = CardDesign.BOARD)))
        assertTrue(style.matches(applied.with(CardOption.DISPLAY_FONT, "caveat")))
    }

    @Test
    fun aCustomTileCarriesTheCardsLayout() {
        val styles = cardStylesFor(CardDesign.BOARD)
        val edited = CardOverrides().withStyle(styles[1], CardDesign.BOARD).with(CardOption.ACCENT, "#123456").with(CardOption.PORTRAIT_SHAPE, "ellipse")
        val custom = customStyle(styles, edited, CardDesign.BOARD)!!
        assertEquals("ellipse", custom.portrait)
        assertEquals("#123456", custom.accent)
        assertEquals(styles[1].ground, custom.ground)
        assertFalse(custom.isDefault)
    }

    @Test
    fun aCardOnARetiredGroundShowsAsCustom() {
        val legacy = CardDesignSpecs.of(CardDesign.COLLAGE)!!.legacySchemes.first()
        val saved = CardOverrides(palette = legacy.applyTo(null))
        val styles = cardStylesFor(CardDesign.COLLAGE)
        assertEquals(legacy.ground, customStyle(styles, saved, CardDesign.COLLAGE)?.ground)
    }

    @Test
    fun manualOptionsStillWorkAfterAStyle() {
        val style = cardStylesFor(CardDesign.BOARD)[1]
        val applied = CardOverrides().withStyle(style, CardDesign.BOARD).with(CardOption.DISPLAY_FONT, "caveat")
        assertEquals("caveat", applied.valueOf(CardOption.DISPLAY_FONT))
        assertEquals(style.ground, applied.valueOf(CardOption.COLOURS))
    }
}
