package id.homebase.core.vault

import id.homebase.core.sync.createTestDatabaseManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class VaultPreferencesAuthSessionTest {

    private class MutableClock(var current: Instant) : Clock {
        override fun now(): Instant = current
        operator fun plusAssign(delta: Duration) { current += delta }
    }

    private fun clock() = MutableClock(Instant.fromEpochMilliseconds(1_000_000_000L))

    // (1) Active in-Vault use must never idle-lock, no matter how long.
    @Test
    fun `active vault session survives idle beyond five minutes`() = runTest {
        val clock = clock()
        val prefs = VaultPreferences(createTestDatabaseManager(), clock)
        prefs.recordAuthSuccess()
        prefs.setVaultScreenActive(true)

        clock += 6.minutes // no user action recorded during this stretch (typing a note)

        assertTrue(prefs.isAuthSessionValid())
    }

    // (2) A genuine background beyond the threshold still re-locks, even while active.
    @Test
    fun `active vault session still re-locks after a genuine background`() = runTest {
        val clock = clock()
        val prefs = VaultPreferences(createTestDatabaseManager(), clock)
        prefs.recordAuthSuccess()
        prefs.setVaultScreenActive(true)

        clock += 1.minutes // use the vault for a bit, then leave
        prefs.recordAppBackgrounded()
        clock += 31.seconds

        assertFalse(prefs.isAuthSessionValid())
    }

    // (2b) A background recorded within the settle window right after a successful unlock is
    // ignored (biometric-prompt lifecycle churn on some devices), so it must NOT re-lock.
    @Test
    fun `background right after unlock is ignored and does not re-lock`() = runTest {
        val clock = clock()
        val prefs = VaultPreferences(createTestDatabaseManager(), clock)
        prefs.recordAuthSuccess()
        prefs.setVaultScreenActive(true)

        clock += 100.milliseconds // biometric prompt stops the Activity ~right after auth
        prefs.recordAppBackgrounded()
        clock += 5.minutes // later, well past the 30s background threshold

        assertTrue(prefs.isAuthSessionValid())
    }

    // (3) When NOT active, the old 5-minute idle auto-lock still applies.
    @Test
    fun `inactive vault session idle-locks after five minutes`() = runTest {
        val clock = clock()
        val prefs = VaultPreferences(createTestDatabaseManager(), clock)
        prefs.recordAuthSuccess()
        // isVaultScreenActive stays false

        clock += 6.minutes

        assertFalse(prefs.isAuthSessionValid())
    }

    // Guard: a fresh session (never authenticated) is invalid.
    @Test
    fun `unauthenticated session is invalid`() = runTest {
        val prefs = VaultPreferences(createTestDatabaseManager(), clock())
        assertFalse(prefs.isAuthSessionValid())
    }
}
