package id.homebase.core.ui.navigation

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic coverage for the notification-tap back-stack gate (PR #322), replacing the
 * two runComposeUiTest cases that crash on Compose-Multiplatform's desktop (Skiko) scene
 * teardown — see the @Ignore'd warm_start_… / broken_top_only_… tests in
 * [NotificationTapColdStartTest].
 *
 * The production gate ([firstContaining], used by AppNavHost) must resolve when
 * Route.ChatList is *anywhere* in the back stack, not only when it is on top. The old
 * top-only gate (`currentBackStackEntryFlow.first { it == ChatList }`) hangs forever when
 * the user is warm on a Detail screen, silently dropping the tap.
 *
 * The back stack is modeled as a [MutableStateFlow] — faithful because
 * `navController.currentBackStack` is itself a StateFlow that never completes, so a
 * never-matching top-only gate genuinely suspends and `withTimeoutOrNull` (auto-advanced by
 * `runTest` virtual time) returns null instantly.
 */
class BackStackGateTest {

    @Test
    fun membership_gate_resolves_when_chatlist_is_below_top() = runTest {
        // Warm on Detail: ChatList sits *below* the top entry.
        val backStack = MutableStateFlow(listOf("ChatList", "Detail"))

        val result = backStack.firstContaining { it == "ChatList" }

        assertEquals(listOf("ChatList", "Detail"), result)
    }

    @Test
    fun membership_gate_suspends_until_chatlist_pushed() = runTest {
        // Cold start: gate fires while ChatList is not yet in the stack, then resolves
        // once it is pushed.
        val backStack = MutableStateFlow(listOf("AppLoading"))

        val gated = async { backStack.firstContaining { it == "ChatList" } }
        backStack.value = listOf("ChatList")

        assertEquals(listOf("ChatList"), gated.await())
    }

    @Test
    fun top_only_gate_hangs_when_chatlist_is_below_top() = runTest {
        // Negative control: the pre-fix top-only gate over the same warm-on-Detail stack
        // never matches (top is Detail) and suspends forever.
        val backStack = MutableStateFlow(listOf("ChatList", "Detail"))

        val resolved = withTimeoutOrNull(5.seconds) {
            backStack.first { stack -> stack.last() == "ChatList" }
        }

        assertNull(resolved, "top-only gate must hang while ChatList is below the top")
    }
}
