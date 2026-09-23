package id.homebase.core.ui.screens.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardTapShareGateTest {
    private val on = CardTapShareGate(profileCardEnabled = true, tapShareEnabled = true, odinId = "frodo.dotyou.cloud", design = CardDesign.POSTER)

    @Test
    fun servesTheCardLinkWithTheSavedDesign() {
        assertEquals("https://frodo.dotyou.cloud/card?design=poster", on.servedUrl)
    }

    @Test
    fun noSavedDesignLeavesTheDesignToThePage() {
        assertEquals("https://frodo.dotyou.cloud/card", on.copy(design = null).servedUrl)
    }

    @Test
    fun tapShareDisabledServesNothing() {
        assertNull(on.copy(tapShareEnabled = false).servedUrl)
    }

    @Test
    fun devFlagOffServesNothing() {
        assertNull(on.copy(profileCardEnabled = false).servedUrl)
    }

    @Test
    fun loggedOutServesNothing() {
        assertNull(on.copy(odinId = null).servedUrl)
    }

    @Test
    fun debounceReportsOncePerLingeringTap() {
        val debounce = TapShareDebounce(windowMs = 3_000)
        assertTrue(debounce.accept(10_000))
        assertFalse(debounce.accept(11_000))
        assertFalse(debounce.accept(13_500))
        assertTrue(debounce.accept(17_000))
    }
}
