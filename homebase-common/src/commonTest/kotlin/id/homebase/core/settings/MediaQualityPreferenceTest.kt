package id.homebase.core.settings

import id.homebase.api.image.MediaQuality
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #1369 ships Standard as the default for new *and* existing installs. Because the key is new,
 * that is carried entirely by the getter's fallback — there is no migration to get wrong, but
 * there is also nothing else stopping a bad default from reaching everyone.
 */
class MediaQualityPreferenceTest {

    @Test
    fun unsetKeyReadsStandard() {
        assertEquals(MediaQuality.STANDARD, UserPreferences(InMemorySettings()).mediaQuality)
    }

    @Test
    fun roundTripsAndMirrorsIntoPreferenceState() {
        val prefs = UserPreferences(InMemorySettings())

        prefs.mediaQuality = MediaQuality.HIGH

        assertEquals(MediaQuality.HIGH, prefs.mediaQuality)
        // The composer's HD chip and the settings screen both read the mirrored flow.
        assertEquals(MediaQuality.HIGH, prefs.preferenceState.value.mediaQuality)
    }

    @Test
    fun anUnrecognisedStoredValueFallsBackToStandard() {
        val settings = InMemorySettings()
        settings.putString("media_quality", "ultra-hd-from-a-future-build")

        assertEquals(MediaQuality.STANDARD, UserPreferences(settings).mediaQuality)
    }
}
