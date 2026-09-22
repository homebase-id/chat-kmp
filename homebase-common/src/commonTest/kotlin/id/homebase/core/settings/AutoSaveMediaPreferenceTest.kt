package id.homebase.core.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSaveMediaPreferenceTest {

    @Test
    fun autoSaveIsOffAndTheWifiGuardIsOnByDefault() {
        val prefs = UserPreferences(InMemorySettings())

        assertFalse(prefs.autoSaveIncomingMedia)
        assertTrue(prefs.autoSaveOnUnmeteredOnly)
        assertFalse(prefs.preferenceState.value.autoSaveIncomingMedia)
        assertTrue(prefs.preferenceState.value.autoSaveOnUnmeteredOnly)
    }

    @Test
    fun bothRoundTripAndMirrorIntoPreferenceState() {
        val prefs = UserPreferences(InMemorySettings())

        prefs.autoSaveIncomingMedia = true
        prefs.autoSaveOnUnmeteredOnly = false

        assertTrue(prefs.autoSaveIncomingMedia)
        assertFalse(prefs.autoSaveOnUnmeteredOnly)
        // The settings screen and the background service both read the mirrored flow.
        assertTrue(prefs.preferenceState.value.autoSaveIncomingMedia)
        assertFalse(prefs.preferenceState.value.autoSaveOnUnmeteredOnly)
    }
}
