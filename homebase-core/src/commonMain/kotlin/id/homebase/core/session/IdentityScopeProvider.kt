package id.homebase.core.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.koinInject
import org.koin.compose.scope.UnboundKoinScope
import org.koin.core.annotation.KoinDelicateAPI
import org.koin.core.annotation.KoinExperimentalAPI

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
@OptIn(KoinExperimentalAPI::class, KoinDelicateAPI::class)
@Composable
fun IdentityScopeProvider(content: @Composable () -> Unit) {
    val session = koinInject<IdentitySessionScope>()
    val scope by session.currentScope.collectAsState()

    val live = scope
    if (live != null && !live.closed) {
        UnboundKoinScope(live) { content() }
    } else {
        content()
    }
}
