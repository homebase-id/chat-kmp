package id.homebase.core.email

import id.homebase.core.sync.createTestDatabaseManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class EmailPreferencesAuthSessionTest {

    private class MutableClock(var current: Instant) : Clock {
        override fun now(): Instant = current
        operator fun plusAssign(delta: Duration) { current += delta }
    }

    private fun clock() = MutableClock(Instant.fromEpochMilliseconds(1_000_000_000L))

    @Test
    fun `unauthenticated session requires unlock`() = runTest {
        val prefs = EmailPreferences(createTestDatabaseManager(), clock())
        assertTrue(prefs.requiresUnlock())
    }

    @Test
    fun `background four minutes keeps the session`() = runTest {
        val clock = clock()
        val prefs = EmailPreferences(createTestDatabaseManager(), clock)
        prefs.recordAuthSuccess()
        prefs.setEmailScreenActive(true)
        clock += 1.minutes
        prefs.recordAppBackgrounded()
        clock += 4.minutes
        assertFalse(prefs.requiresUnlock())
    }

    @Test
    fun `background six minutes re-prompts`() = runTest {
        val clock = clock()
        val prefs = EmailPreferences(createTestDatabaseManager(), clock)
        prefs.recordAuthSuccess()
        prefs.setEmailScreenActive(true)
        clock += 1.minutes
        prefs.recordAppBackgrounded()
        clock += 6.minutes
        assertTrue(prefs.requiresUnlock())
    }

    @Test
    fun `background inside the settle window is ignored`() = runTest {
        val clock = clock()
        val prefs = EmailPreferences(createTestDatabaseManager(), clock)
        prefs.recordAuthSuccess()
        prefs.setEmailScreenActive(true)
        clock += 100.milliseconds
        prefs.recordAppBackgrounded()
        clock += 10.minutes
        assertFalse(prefs.requiresUnlock())
    }

    @Test
    fun `active email flow never idle-locks`() = runTest {
        val clock = clock()
        val prefs = EmailPreferences(createTestDatabaseManager(), clock)
        prefs.recordAuthSuccess()
        prefs.setEmailScreenActive(true)
        clock += 20.minutes
        assertFalse(prefs.requiresUnlock())
    }

    @Test
    fun `leaving the flow starts the five minute clock`() = runTest {
        val clock = clock()
        val prefs = EmailPreferences(createTestDatabaseManager(), clock)
        prefs.recordAuthSuccess()
        prefs.setEmailScreenActive(true)
        clock += 20.minutes
        prefs.setEmailScreenActive(false)
        clock += 4.minutes
        assertFalse(prefs.requiresUnlock())
        clock += 2.minutes
        assertTrue(prefs.requiresUnlock())
    }

    @Test
    fun `toggle off never gates`() = runTest {
        val prefs = EmailPreferences(createTestDatabaseManager(), clock())
        prefs.setBiometricsEnabled(false)
        assertFalse(prefs.requiresUnlock())
    }
}
