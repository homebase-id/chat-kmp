package id.homebase.core.ui.screens.privatedrive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.privatedrive.PhotosPrivateDrive
import id.homebase.core.privatedrive.PrivateDriveOptIn
import id.homebase.resources.MR
import id.homebase.resources.photobase_sign_in_again_body
import id.homebase.resources.photobase_sign_in_again_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** The Photos status screen's "sign in again" banner, shown while this device has no key for the private Photos drive. */
@Composable
fun PhotosSignInAgainBanner(optIn: PrivateDriveOptIn = koinInject()) {
    val scope = rememberCoroutineScope()
    val state by remember(optIn) { optIn.state(PhotosPrivateDrive) }.collectAsStateWithLifecycle(optIn.currentState(PhotosPrivateDrive))
    val signIn = rememberSignInAgainLauncher { url -> scope.launch { optIn.handleSignInCallback(url) } }
    if (!state.needsSignIn) return
    SignInAgainBanner(
        title = stringResource(MR.string.photobase_sign_in_again_title),
        body = stringResource(MR.string.photobase_sign_in_again_body),
        blocked = signIn.blockedUrl != null,
        onSignIn = { signIn.blockedUrl?.let(signIn::open) ?: scope.launch { optIn.signInAgain(open = signIn::open, failed = {}) } },
    )
}
