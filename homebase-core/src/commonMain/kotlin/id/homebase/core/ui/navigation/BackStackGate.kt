package id.homebase.core.ui.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Suspends until this flow emits a list containing at least one element matching
 * [predicate], then returns that list.
 *
 * Used to gate notification-tap navigation on [Route.ChatList] being *anywhere* in the
 * NavController back stack, not just on top. A top-of-stack gate hangs forever when the
 * user is warm on a Detail/Settings screen, silently dropping the tap (PR #322 warm-path
 * regression). Membership gating resolves as soon as ChatList sits anywhere in the stack.
 *
 * Extracted as a pure Flow helper so the regression is covered by a deterministic coroutine
 * test ([id.homebase.core.ui.navigation.BackStackGateTest]) instead of a live NavController
 * under runComposeUiTest, whose Skiko desktop scene teardown crashes when destroying a
 * still-INITIALIZED NavBackStackEntry.
 */
suspend fun <T> Flow<List<T>>.firstContaining(predicate: (T) -> Boolean): List<T> =
    first { list -> list.any(predicate) }
