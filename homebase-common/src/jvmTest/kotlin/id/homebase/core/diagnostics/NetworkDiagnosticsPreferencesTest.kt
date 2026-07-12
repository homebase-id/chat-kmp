package id.homebase.core.diagnostics

import id.homebase.core.sync.createTestDatabaseManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkDiagnosticsPreferencesTest {

    @Test
    fun `stores and reads back the last-known IP for the same hostname`() = runTest {
        val prefs = NetworkDiagnosticsPreferences(createTestDatabaseManager())
        prefs.setLastKnownIp("frodo.example", "203.0.113.7", atMs = 1_700_000_000_000L)

        val got = prefs.getLastKnownIp("frodo.example")
        assertEquals(LastKnownIp("203.0.113.7", 1_700_000_000_000L), got)
    }

    @Test
    fun `ignores a stored IP that belongs to a different hostname`() = runTest {
        val prefs = NetworkDiagnosticsPreferences(createTestDatabaseManager())
        prefs.setLastKnownIp("frodo.example", "203.0.113.7", atMs = 1L)

        // A different owner/server — the stale entry must not be reused.
        assertNull(prefs.getLastKnownIp("samwise.example"))
    }

    @Test
    fun `returns null when nothing has been stored`() = runTest {
        val prefs = NetworkDiagnosticsPreferences(createTestDatabaseManager())
        assertNull(prefs.getLastKnownIp("frodo.example"))
    }

    @Test
    fun `latest write wins for the same hostname`() = runTest {
        val prefs = NetworkDiagnosticsPreferences(createTestDatabaseManager())
        prefs.setLastKnownIp("frodo.example", "203.0.113.7", atMs = 1L)
        prefs.setLastKnownIp("frodo.example", "198.51.100.9", atMs = 2L)

        assertEquals(LastKnownIp("198.51.100.9", 2L), prefs.getLastKnownIp("frodo.example"))
    }
}
