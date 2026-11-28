package id.homebase.core.ui.screens.privatedrive

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.resources.MR
import id.homebase.resources.photobase_sign_in_again_body
import id.homebase.resources.photobase_sign_in_again_title
import id.homebase.resources.sign_in_again_failed
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The Photos status screen's "sign in again" banner, shown while this device has no key for the private Photos drive. */
@Composable
fun PhotosSignInAgainBanner(viewModel: PhotosSignInAgainViewModel = koinViewModel()) {
    val needsSignIn by viewModel.needsSignIn.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val signIn = rememberSignInAgainLauncher(viewModel::onSignInCallback)
    if (!needsSignIn) return
    SignInAgainBanner(
        title = stringResource(MR.string.photobase_sign_in_again_title),
        body = stringResource(MR.string.photobase_sign_in_again_body),
        blocked = signIn.blockedUrl != null,
        onSignIn = { signIn.blockedUrl?.let(signIn::open) ?: viewModel.signInAgain(signIn::open) },
    )
    if (failed) {
        Text(
            stringResource(MR.string.sign_in_again_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
