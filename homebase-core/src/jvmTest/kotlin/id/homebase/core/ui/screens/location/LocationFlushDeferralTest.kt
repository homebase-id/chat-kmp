package id.homebase.core.ui.screens.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the battery-saver upload-deferral policy (#878 follow-up): defer a flush ONLY while saver is
 * on AND the app is backgrounded AND there's no un-uploaded backlog past the 24h safety threshold.
 * A foregrounded user always uploads; a >24h backlog always uploads.
 */
class LocationFlushDeferralTest {

    @Test
    fun defersOnlyWhenSaverOnAndBackgroundedAndNoStaleBacklog() {
        assertTrue(
            shouldDeferBackgroundFlush(
                powerSaveMode = true,
                appForeground = false,
                hasStaleUnuploaded = false,
            )
        )
    }

    @Test
    fun foregroundAlwaysUploads() {
        assertFalse(
            shouldDeferBackgroundFlush(
                powerSaveMode = true,
                appForeground = true,
                hasStaleUnuploaded = false,
            )
        )
    }

    @Test
    fun staleBacklogOverridesSaver() {
        // >24h of un-uploaded points: upload even though saver is on and we're backgrounded.
        assertFalse(
            shouldDeferBackgroundFlush(
                powerSaveMode = true,
                appForeground = false,
                hasStaleUnuploaded = true,
            )
        )
    }

    @Test
    fun noSaverNeverDefers() {
        for (foreground in listOf(true, false)) {
            for (stale in listOf(true, false)) {
                assertEquals(
                    false,
                    shouldDeferBackgroundFlush(
                        powerSaveMode = false,
                        appForeground = foreground,
                        hasStaleUnuploaded = stale,
                    ),
                    "no-saver must never defer (foreground=$foreground stale=$stale)",
                )
            }
        }
    }
}
