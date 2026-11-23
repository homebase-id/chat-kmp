package id.homebase.core.ui.screens.contactbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactTabsTest {
    @Test
    fun callsSitsSecondAndOnlyWhenCallingIsAvailable() {
        assertEquals(
            listOf(ContactTab.KNOWN, ContactTab.CALLS, ContactTab.NEW, ContactTab.CIRCLES),
            contactTabs(callsAvailable = true),
        )
        assertEquals(
            listOf(ContactTab.KNOWN, ContactTab.NEW, ContactTab.CIRCLES),
            contactTabs(callsAvailable = false),
        )
    }

    @Test
    fun contactsIsStillTheDefaultTab() {
        assertEquals(ContactTab.KNOWN, ContactBookUiState().selectedTab)
    }

    @Test
    fun onlyCallsSkipsTheOnboardingIntro() {
        assertFalse(ContactTab.CALLS.showsOnboarding(onboardingComplete = false))
        assertTrue(ContactTab.KNOWN.showsOnboarding(onboardingComplete = false))
        assertTrue(ContactTab.NEW.showsOnboarding(onboardingComplete = false))
        assertTrue(ContactTab.CIRCLES.showsOnboarding(onboardingComplete = false))
        ContactTab.entries.forEach { assertFalse(it.showsOnboarding(onboardingComplete = true)) }
    }
}
