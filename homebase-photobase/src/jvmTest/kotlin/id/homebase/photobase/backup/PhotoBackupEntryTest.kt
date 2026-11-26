package id.homebase.photobase.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class PhotoBackupEntryTest {
    private var builds = 0

    private fun entry(failure: Exception = IllegalStateException("no controller")) =
        PhotoBackupEntry {
            builds++
            throw failure
        }

    @Test
    fun aControllerThatFailsToBuildStopsAtTheEntry() = runTest {
        val entry = entry()

        entry.onProcessStart()
        entry.onAppForeground()
        entry.onAppBackground()
        entry.onPostAuthenticated()
        entry.onBeforeLogout()
        entry.onWake(10.seconds)

        assertEquals(6, builds)
    }

    @Test
    fun cancellationStillPropagates() = runTest {
        val entry = entry(failure = CancellationException("cancelled"))

        assertFailsWith<CancellationException> { entry.onWake(10.seconds) }
    }
}
