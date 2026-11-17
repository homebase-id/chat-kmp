package id.homebase.core.ui.screens.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

class CardNfcSheetGateTest {
    private val closed = CardTapShareGate(profileCardEnabled = true, tapShareEnabled = false, odinId = "frodo.dotyou.cloud", design = null)

    @Test
    fun openSheetServesTheCardWithoutTheSettingsSwitch() {
        assertNull(closed.servedUrl)
        assertEquals("https://frodo.dotyou.cloud/card", closed.copy(sheetOpen = true).servedUrl)
    }

    @Test
    fun settingsSwitchKeepsServingAfterTheSheetCloses() {
        assertEquals("https://frodo.dotyou.cloud/card", closed.copy(tapShareEnabled = true, sheetOpen = false).servedUrl)
    }

    @Test
    fun openSheetStillNeedsTheDevFlagAndALogin() {
        assertNull(closed.copy(sheetOpen = true, profileCardEnabled = false).servedUrl)
        assertNull(closed.copy(sheetOpen = true, odinId = null).servedUrl)
    }

    @Test
    fun serviceIsRegisteredOnlyWhileSheetIsOpen() {
        val open = closed.copy(sheetOpen = true).servedUrl
        assertEquals(listOf(HceStep.Enable, HceStep.Prefer), hceSteps(resumed = true, servedUrl = open, enabled = false, preferred = false))
        assertEquals(listOf(HceStep.Unprefer, HceStep.Disable), hceSteps(resumed = true, servedUrl = closed.servedUrl, enabled = true, preferred = true))
    }

    @Test
    fun panelCopyIsPickedPerDevice() {
        assertEquals(CardNfcPanel.Iphone, cardNfcPanel(canShare = false, nfcEnabled = false))
        assertEquals(CardNfcPanel.AndroidReady, cardNfcPanel(canShare = true, nfcEnabled = true))
        assertEquals(CardNfcPanel.AndroidOff, cardNfcPanel(canShare = true, nfcEnabled = false))
        assertNotEquals(CardNfcPanel.Iphone.bodyRes, CardNfcPanel.AndroidReady.bodyRes)
        assertNotEquals(CardNfcPanel.Iphone.titleRes, CardNfcPanel.AndroidReady.titleRes)
    }
}
