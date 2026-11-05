package id.homebase.photobase.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class PhotoBackupEntryTest {
    private var builds = 0

    private fun entry(enabled: Boolean, failure: Exception = IllegalStateException("no controller")) =
        PhotoBackupEntry({ enabled }) {
            builds++
            throw failure
        }

    @Test
    fun whileDarkTheControllerIsNeverBuilt() = runTest {
        val entry = entry(enabled = false)

        entry.onProcessStart()
        entry.onAppForeground()
        entry.onAppBackground()
        entry.onPostAuthenticated()
        entry.onBeforeLogout()
        entry.onWake(10.seconds)

        assertEquals(0, builds)
    }

    @Test
    fun aControllerThatFailsToBuildStopsAtTheEntry() = runTest {
        val entry = entry(enabled = true)

        entry.onProcessStart()
        entry.onAppForeground()
        entry.onAppBackground()
        entry.onPostAuthenticated()
        entry.onBeforeLogout()
        entry.onWake(10.seconds)

        assertEquals(6, builds)
    }

    @Test
    fun turningItOnTakesEffectWithoutARestart() = runTest {
        var on = false
        val entry = PhotoBackupEntry({ on }) {
            builds++
            throw IllegalStateException("no controller")
        }

        entry.onProcessStart()
        assertEquals(0, builds)

        on = true
        entry.onProcessStart()
        assertEquals(1, builds)
    }

    @Test
    fun cancellationStillPropagates() = runTest {
        val entry = entry(enabled = true, failure = CancellationException("cancelled"))

        assertFailsWith<CancellationException> { entry.onWake(10.seconds) }
    }
}
