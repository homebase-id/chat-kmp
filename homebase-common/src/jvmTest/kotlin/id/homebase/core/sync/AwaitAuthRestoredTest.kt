package id.homebase.core.sync

import id.homebase.api.youauth.YouAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Locks down the [awaitAuthRestored] helper that backs the FCM
 * credential-restore race fix in [BackgroundSyncOrchestrator].
 *
 * Background: on FCM cold-wake, `YouAuthFlowManager.restoreSession()`
 * runs asynchronously and stamps credentials only after disk I/O. Prior
 * to the fix, `syncIfAuthenticated` lost a ~12 ms race against that
 * restoration and silently bailed with "no credentials, skipping",
 * preventing the BG sync from running for a foreground-killed app.
 *
 * The helper waits for `authState` to leave `Initializing` with a
 * timeout. These tests use virtual time so they run instantly and stay
 * deterministic.
 */
class AwaitAuthRestoredTest {

    private val authenticated =
        YouAuthState.Authenticated(
            identity = id.homebase.api.common.OdinId("test.example.com"),
            clientAuthToken = "tok",
            sharedSecret = "sec",
        )

    @Test
    fun returnsImmediately_whenAlreadyResolved() = runTest {
        // No race — authState was already populated by the time we asked.
        val authState = MutableStateFlow<YouAuthState>(authenticated)

        val resolved = awaitAuthRestored(authState, 2.seconds)

        assertNotNull(resolved)
        assertSame(authenticated, resolved)
    }

    @Test
    fun returnsImmediately_whenAlreadyUnauthenticated() = runTest {
        // Logged-out cold-wake still resolves — caller will then short-circuit
        // on hasActiveCredentials(). The point is we don't hang.
        val authState = MutableStateFlow<YouAuthState>(YouAuthState.Unauthenticated)

        val resolved = awaitAuthRestored(authState, 2.seconds)

        assertEquals(YouAuthState.Unauthenticated, resolved)
    }

    @Test
    fun awaits_whenInitializing_thenResolvesAfterTransition() = runTest {
        // The exact scenario from homebase.log 2026-06-01: a tight race where
        // authState is still Initializing at the call site, then flips
        // ~12 ms later as restoreSession() completes.
        val authState = MutableStateFlow<YouAuthState>(YouAuthState.Initializing)

        val job = launch {
            val resolved = awaitAuthRestored(authState, 2.seconds)
            assertNotNull(resolved, "should resolve once authState leaves Initializing")
            assertSame(authenticated, resolved)
        }

        // Simulate the disk restoration completing after a short delay,
        // well inside the timeout.
        launch {
            kotlinx.coroutines.delay(12.milliseconds)
            authState.value = authenticated
        }

        job.join()
    }

    @Test
    fun timesOut_whenStaysInitializing() = runTest {
        // Wedged restoration — credentials never arrive within the budget.
        // The helper must return null so the caller can treat it as a skip
        // (and not hang the WorkManager job).
        val authState = MutableStateFlow<YouAuthState>(YouAuthState.Initializing)

        val resolved = awaitAuthRestored(authState, 2.seconds)

        assertNull(resolved, "must return null on timeout, not hang")
    }

    @Test
    fun timesOut_whenResolutionRacesPastDeadline() = runTest {
        // Resolution arrives JUST after the timeout — must still be treated
        // as a timeout. Guards against an off-by-one where a state change at
        // exactly `timeout` would be picked up.
        val authState = MutableStateFlow<YouAuthState>(YouAuthState.Initializing)

        launch {
            kotlinx.coroutines.delay(3.seconds)
            authState.value = authenticated
        }

        val resolved = awaitAuthRestored(authState, 2.seconds)

        assertNull(resolved)
    }
}
