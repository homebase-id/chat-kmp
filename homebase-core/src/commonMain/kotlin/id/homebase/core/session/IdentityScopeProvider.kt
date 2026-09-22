package id.homebase.core.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.koin.compose.ComposeContextWrapper
import org.koin.compose.LocalKoinScopeContext
import org.koin.compose.currentKoinScope
import org.koin.compose.koinInject
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.scope.Scope

/**
 * Makes the open identity session the scope that `koinViewModel()` and `koinInject()` resolve
 * from inside [content].
 *
 * Everything identity-scoped — the services and the ViewModels that consume them — is
 * registered in that scope, and a definition can only reach scoped dependencies if it is
 * resolved from the scope itself (see `ScopeResolutionMechanicsTest`). This provider is what
 * makes that true for the whole UI without touching individual call sites.
 *
 * While logged out there is no scope, and [content] resolves from the root as before — which
 * is what the login screens need. Definitions registered at root keep resolving normally
 * either way; scope membership only affects what a definition can *see*.
 *
 * Deliberately "unbound": the scope's lifetime belongs to
 * [id.homebase.core.auth.AuthConnectionCoordinator], which opens it on the authenticated
 * transition and closes it on logout. Binding it to composition instead would destroy every
 * per-identity service whenever this subtree left the tree.
 */
@Composable
fun IdentityScopeProvider(content: @Composable () -> Unit) {
    val session = koinInject<IdentitySessionScope>()
    val published by session.currentScope.collectAsState()
    val liveScope = remember(session) { session::scopeOrNull }
    IdentityScope(published, liveScope, content)
}

/**
 * The provider minus its subscription, so a test can hold [published] at the value the
 * composition is stuck with while the scope dies underneath it (#1373).
 *
 * [published] arrives through `collectAsState`; `Scope.closed` flips synchronously inside
 * `IdentitySessionScope.close()`. In between, anything below that recomposes for its own
 * reasons reads a closed scope — and `UnboundKoinScope` publishes a [ComposeContextWrapper]
 * with no `setValue`, so `currentKoinScope()`'s recovery path degenerates into a throw that
 * `GlobalCrashHandler` turns into a process kill. Every other Koin entry point publishes a
 * wrapper *with* one; this does the same, pointing at whatever scope is live now — the next
 * identity's after a switch, the root once logged out.
 *
 * Ceiling: a definition registered only in the identity scope has no answer once that scope is
 * gone, same as `requireScope()`, so it trades a closed-scope throw for a missing-definition one.
 *
 * [LocalKoinScopeContext] and [ComposeContextWrapper] are `@KoinInternalApi`: a Koin upgrade
 * that seals them breaks this at compile time rather than silently.
 */
@OptIn(KoinInternalApi::class)
@Composable
internal fun IdentityScope(
    published: Scope?,
    liveScope: () -> Scope?,
    content: @Composable () -> Unit,
) {
    val fallback = currentKoinScope()
    val live = published?.takeIf { !it.closed }
    if (live != null) {
        val scopeContext = remember(live, fallback) {
            ComposeContextWrapper(live) { liveScope() ?: fallback }
        }
        CompositionLocalProvider(LocalKoinScopeContext provides scopeContext) { content() }
    } else {
        content()
    }
}
