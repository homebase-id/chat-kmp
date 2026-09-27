package id.homebase.core.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MirrorFrontCameraPreferenceTest {

    @Test
    fun mirroringIsOnByDefault() {
        val prefs = UserPreferences(InMemorySettings())

        assertTrue(prefs.mirrorFrontCamera)
        assertTrue(prefs.preferenceState.value.mirrorFrontCamera)
    }

    @Test
    fun turningItOffPersistsAndMirrorsIntoPreferenceState() {
        val settings = InMemorySettings()
        val prefs = UserPreferences(settings)

        prefs.mirrorFrontCamera = false

        assertFalse(prefs.preferenceState.value.mirrorFrontCamera)
        val reopened = UserPreferences(settings)
        assertFalse(reopened.mirrorFrontCamera)
        assertFalse(reopened.preferenceState.value.mirrorFrontCamera)
    }
}
