package id.homebase.core.ui.screens.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileCardStylesTest {

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
    fun defaultComesFirstThenOneStylePerSchemeInOrder() {
        for (design in CardDesign.all) {
            val schemes = CardDesignSpecs.of(design)!!.schemes.map { it.id }
            val styles = cardStylesFor(design)
            assertEquals(listOf(DEFAULT_STYLE_ID) + schemes, styles.map { it.id }, design)
            assertEquals(listOf(true) + List(styles.size - 1) { false }, styles.map { it.isDefault }, design)
        }
    }

    // The odin-js presets.ts look each page draws with no colours set; the tile's accent is left to the thumbnail's copy of it.
    @Test
    fun theDefaultTileIsTheBuiltInLook() {
        val presets = mapOf(
            CardDesign.POSTER to ("#14120F" to "#F4F0EA"),
            CardDesign.BOARD to ("#1F4E8C" to "#FFFFFF"),
            CardDesign.COLLAGE to ("#F3EADB" to "#3A2E22"),
            CardDesign.DOSSIER to ("#0E1013" to "#E9ECF1"),
            CardDesign.LINKS to ("#F1F3F6" to "#1B1F24"),
        )
        for (design in CardDesign.all) {
            val style = default(design)
            assertEquals(presets.getValue(design), style.ground to style.ink, design)
            assertNull(style.accent, design)
        }
    }

    @Test
    fun everyDesignOffersAtLeastFiveColourOnlyStylesBuiltFromItsOwnValues() {
        for (design in CardDesign.all) {
            val spec = CardDesignSpecs.of(design)!!
            val styles = cardStylesFor(design)
            assertTrue(styles.size >= 5, "$design offers ${styles.size}")
            for (style in styles.filterNot { it.isDefault }) {
                val scheme = spec.schemes.single { it.id == style.id }
                assertEquals(scheme.ground, style.ground)
                assertEquals(scheme.ink, style.ink)
                val accent = if (CardOption.ACCENT !in spec.options) null else scheme.accents.first()
                assertEquals(accent, style.accent, "$design ${style.id}")
                assertTrue(listOf(style.portrait, style.socials, style.blocks).all { it == null }, "$design ${style.id}")
            }
        }
    }

    @Test
    fun posterOnlyGetsItsDarkGrounds() {
        val poster = CardDesignSpecs.of(CardDesign.POSTER)!!.schemes.map { it.ground } + argbToHex(CardDesign.baseArgb(CardDesign.POSTER))
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
        for (design in CardDesign.all) for (style in cardStylesFor(design).filterNot { it.isDefault }) {
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
        for (design in CardDesign.all) {
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
    fun defaultClearsOnlyTheColoursAndAccentLikeTheColoursDefault() {
        for (design in CardDesign.all) {
            val spec = CardDesignSpecs.of(design)!!
            val before = styled(design).withBlockOrder(spec.presetOrder.reversed()).prunedFor(design)
            val cleared = before.withStyle(default(design), design)
            assertNull(cleared.palette, design)
            assertEquals(before.copy(palette = null), cleared, design)
            assertEquals(before.with(CardOption.COLOURS, null, design = design).with(CardOption.ACCENT, null).prunedFor(design), cleared, design)
            assertTrue(default(design).matches(cleared), design)
        }
    }

    @Test
    fun aBareCardSelectsDefault() {
        for (design in CardDesign.all) {
            val styles = cardStylesFor(design)
            assertEquals(listOf(styles.first().id), styles.filter { it.matches(CardOverrides()) }.map { it.id }, design)
            assertNull(customStyle(styles, CardOverrides(), design), design)
        }
    }

    private fun assertSelects(expected: String, template: CardTemplate) {
        val styles = cardStylesFor(template.design)
        assertEquals(listOf(expected), styles.filter { it.matches(template.overrides) }.map { it.id }, template.toString())
        assertNull(customStyle(styles, template.overrides, template.design), template.toString())
    }

    @Test
    fun anUntouchedFriendsCardSelectsSage() = assertSelects("sage", cardTemplateFor(CardAudience.Circle(FRIENDS_CIRCLE_ID, ""))!!)

    @Test
    fun anUntouchedFamilyCardSelectsWine() = assertSelects("wine", cardTemplateFor(CardAudience.Circle(FAMILY_CIRCLE_ID, ""))!!)

    @Test
    fun anUntouchedWorkCardSelectsCloud() = assertSelects("cloud", cardTemplateFor(CardAudience.Circle(WORK_CIRCLE_ID, ""))!!)

    @Test
    fun anUntouchedPublicCardSelectsMint() = assertSelects("mint", cardTemplateFor(CardAudience.Public)!!)

    // What a templated card stores on its first edit, read back from the server.
    @Test
    fun aCardSavedWithItsTemplatesPaletteStillSelectsThatScheme() {
        val audiences = listOf(FRIENDS_CIRCLE_ID, FAMILY_CIRCLE_ID, WORK_CIRCLE_ID).map { CardAudience.Circle(it, "") } + CardAudience.Public
        for (audience in audiences) {
            val template = cardTemplateFor(audience)!!
            val stored = CardOverrides.fromJson(template.overrides.toJson()).prunedFor(template.design)
            assertSelects(CardDesignSpecs.schemeByGround(template.overrides.palette!!.ground)!!.id, template.copy(overrides = stored))
        }
    }

    @Test
    fun theAccentToolOffersTheCurrentSchemesOwnAccents() {
        for (design in CardDesign.all) {
            val spec = CardDesignSpecs.of(design)!!
            for (scheme in spec.schemes) assertEquals(scheme.accents, CardDesignSpecs.accentsFor(design, scheme.ground), "$design/${scheme.id}")
        }
        for (audience in listOf(FRIENDS_CIRCLE_ID, FAMILY_CIRCLE_ID, WORK_CIRCLE_ID).map { CardAudience.Circle(it, "") } + CardAudience.Public) {
            val template = cardTemplateFor(audience)!!
            val ground = template.overrides.palette!!.ground
            assertEquals(CardDesignSpecs.schemeByGround(ground)!!.accents, CardDesignSpecs.accentsFor(template.design, ground), audience.toString())
        }
    }

    @Test
    fun anAccentPickedAfterDefaultLeavesCustomSelected() {
        for (design in CardDesign.all.filter { CardOption.ACCENT in CardDesignSpecs.of(it)!!.options }) {
            val styles = cardStylesFor(design)
            val accent = CardDesignSpecs.accentsFor(design, null).first()
            val edited = CardOverrides().withStyle(default(design), design).with(CardOption.ACCENT, accent).prunedFor(design)
            assertTrue(styles.none { it.matches(edited) }, design)
            val custom = customStyle(styles, edited, design)!!
            assertEquals(CUSTOM_STYLE_ID, custom.id, design)
            assertEquals(default(design).ground, custom.ground, design)
            assertEquals(default(design).ink, custom.ink, design)
            assertEquals(accent, custom.accent, design)
            assertTrue(default(design).matches(edited.with(CardOption.ACCENT, null)), design)
        }
    }

    @Test
    fun theAppliedStyleIsTheOnlyOneSelected() {
        for (design in CardDesign.all) {
            val styles = cardStylesFor(design)
            for (style in styles) {
                val applied = CardOverrides().withStyle(style, design)
                assertEquals(listOf(style.id), styles.filter { it.matches(applied) }.map { it.id }, "$design ${style.id}")
                assertNull(customStyle(styles, applied, design))
            }
        }
    }

    @Test
    fun onlyColoursOrAnAccentDeselectDefault() {
        val design = CardDesign.BOARD
        val style = default(design)
        val presetOrder = CardDesignSpecs.of(design)!!.presetOrder
        val second = CardDesignSpecs.of(design)!!.schemes[1]
        assertFalse(style.matches(CardOverrides().with(CardOption.COLOURS, second.ground, design = design).prunedFor(design)))
        assertFalse(style.matches(CardOverrides().with(CardOption.ACCENT, second.accents.first()).prunedFor(design)))
        for ((option, value) in listOf(
            CardOption.DISPLAY_FONT to "caveat",
            CardOption.TEXT_FONT to "caveat",
            CardOption.PORTRAIT_SHAPE to "ellipse",
            CardOption.SOCIALS_STYLE to "handles",
            CardOption.BLOCK_STYLE to "row",
        )) {
            val edited = CardOverrides().with(option, value, presetOrder, design).prunedFor(design)
            assertTrue(style.matches(edited), "$option -> $value")
            assertNull(customStyle(cardStylesFor(design), edited, design), "$option -> $value")
        }
        assertTrue(style.matches(CardOverrides().withBlockOrder(presetOrder.reversed()).prunedFor(design)))
    }

    @Test
    fun changingTheColoursOrAccentDeselectsAColourStyle() {
        val style = cardStylesFor(CardDesign.BOARD)[1]
        val applied = CardOverrides().withStyle(style, CardDesign.BOARD)
        assertTrue(style.matches(applied.with(CardOption.ACCENT, null)))
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
    fun aCardOnAnAccentOutsideItsSchemeShowsAsCustom() {
        val sage = CardDesignSpecs.of(CardDesign.COLLAGE)!!.schemes.first()
        val saved = CardOverrides(palette = sage.applyTo(CardPalette(accent = "#123456")))
        assertEquals(sage.ground, customStyle(cardStylesFor(CardDesign.COLLAGE), saved, CardDesign.COLLAGE)?.ground)
    }

    @Test
    fun manualOptionsStillWorkAfterAStyle() {
        val style = cardStylesFor(CardDesign.BOARD)[1]
        val applied = CardOverrides().withStyle(style, CardDesign.BOARD).with(CardOption.DISPLAY_FONT, "caveat")
        assertEquals("caveat", applied.valueOf(CardOption.DISPLAY_FONT))
        assertEquals(style.ground, applied.valueOf(CardOption.COLOURS))
    }
}
