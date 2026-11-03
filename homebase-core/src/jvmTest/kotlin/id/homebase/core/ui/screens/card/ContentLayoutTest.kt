package id.homebase.core.ui.screens.card

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentLayoutTest {
    @Test
    fun flagOffIsAlwaysPanelOnly() {
        assertEquals(ContentLayout.PanelOnly, contentLayout(cardsEnabled = false, wide = true, shortWindow = false))
        assertEquals(ContentLayout.PanelOnly, contentLayout(cardsEnabled = false, wide = false, shortWindow = false))
    }

    @Test
    fun wideShowsTheCardBesideThePanelEvenWhenShort() {
        assertEquals(ContentLayout.Wide, contentLayout(cardsEnabled = true, wide = true, shortWindow = false))
        assertEquals(ContentLayout.Wide, contentLayout(cardsEnabled = true, wide = true, shortWindow = true))
    }

    @Test
    fun compactGetsAHeaderUnlessTheWindowIsShort() {
        assertEquals(ContentLayout.CompactHeader, contentLayout(cardsEnabled = true, wide = false, shortWindow = false))
        assertEquals(ContentLayout.PanelOnly, contentLayout(cardsEnabled = true, wide = false, shortWindow = true))
    }
}
