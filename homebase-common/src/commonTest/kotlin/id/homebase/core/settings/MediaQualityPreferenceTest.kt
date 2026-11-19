package id.homebase.core.settings

import id.homebase.api.image.MediaQuality
import id.homebase.api.image.SendQuality
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each app's photo and video setting, its default, and Chat's start from the one shared setting. */
class MediaQualityPreferenceTest {

    private fun prefs(vararg stored: Pair<String, String>) =
        UserPreferences(InMemorySettings().apply { stored.forEach { (k, v) -> putString(k, v) } })

    @Test
    fun eachAppStartsAtItsDefault() {
        val prefs = prefs()
        assertEquals(SendQuality(MediaQuality.STANDARD, MediaQuality.STANDARD), prefs.sendQuality(SendQualityApp.Chat))
        assertEquals(SendQuality(MediaQuality.HD, MediaQuality.HD), prefs.sendQuality(SendQualityApp.Moments))
        assertEquals(SendQuality(MediaQuality.HD, MediaQuality.HD), prefs.sendQuality(SendQualityApp.Feed))
    }

    @Test
    fun chatStartsFromTheOldSharedSettingAndAnOriginalVideoBecomesHd() {
        val prefs = prefs("media_quality" to MediaQuality.ORIGINAL.code)
        assertEquals(SendQuality(MediaQuality.ORIGINAL, MediaQuality.HD), prefs.sendQuality(SendQualityApp.Chat))
        assertEquals(SendQualityApp.Moments.default, prefs.sendQuality(SendQualityApp.Moments))
        assertEquals(SendQualityApp.Feed.default, prefs.sendQuality(SendQualityApp.Feed))
    }

    @Test
    fun anAppsOwnSettingWinsOverTheOldSharedOne() {
        val prefs = prefs("media_quality" to MediaQuality.HD.code, "chat_photo_quality" to MediaQuality.STANDARD.code)
        assertEquals(SendQuality(MediaQuality.STANDARD, MediaQuality.HD), prefs.sendQuality(SendQualityApp.Chat))
    }

    @Test
    fun roundTripsPerAppAndMirrorsIntoPreferenceState() {
        val prefs = prefs()
        prefs.setSendQuality(SendQualityApp.Moments, SendQuality(MediaQuality.ORIGINAL, MediaQuality.STANDARD))

        assertEquals(SendQuality(MediaQuality.ORIGINAL, MediaQuality.STANDARD), prefs.sendQuality(SendQualityApp.Moments))
        // The composer's chip and the app's page both read the mirrored flow.
        assertEquals(SendQuality(MediaQuality.ORIGINAL, MediaQuality.STANDARD), prefs.preferenceState.value.sendQuality(SendQualityApp.Moments))
        assertEquals(SendQualityApp.Chat.default, prefs.sendQuality(SendQualityApp.Chat))
    }

    @Test
    fun aVideoSettingIsNeverOriginal() {
        val prefs = prefs()
        prefs.setSendQuality(SendQualityApp.Feed, SendQuality(MediaQuality.HD, MediaQuality.ORIGINAL))
        assertEquals(MediaQuality.HD, prefs.sendQuality(SendQualityApp.Feed).video)
    }

    @Test
    fun anUnrecognisedStoredValueFallsBackToTheDefault() {
        val prefs = prefs("moments_photo_quality" to "ultra-hd-from-a-future-build")
        assertEquals(MediaQuality.HD, prefs.sendQuality(SendQualityApp.Moments).photo)
    }
}
