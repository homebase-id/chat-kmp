package id.homebase.auth.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.auth.BrowserLauncher
import id.homebase.core.ui.auth.rememberAuthBrowserLauncher
import id.homebase.core.util.InAppBrowser

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onNavigateHome: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Get platform-specific browser launcher via Compose context
    val launchAuthBrowser = rememberAuthBrowserLauncher()

    // The auth window is opened only after the identity passes its format + ping checks (see
    // LoginViewModel.startLogin). On web that open can be blocked by the popup blocker because it
    // happens after the async ping — in that case we stash the URL and surface a "Continue" button
    // that re-opens it from a fresh click gesture. Always null / unused on native.
    var pendingAuthUrl by remember { mutableStateOf<String?>(null) }

    val openAuth: (String) -> Unit = { url ->
        if (launchAuthBrowser(url)) {
            pendingAuthUrl = null
            // Set up the callback listener now that the auth window is actually open.
            BrowserLauncher.onAuthBrowserOpened(url, viewModel::onCallbackUrl)
        } else {
            pendingAuthUrl = url
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAction(LoginUiAction.AppResumed)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.uiEvent) {
        when (val uiEvent = uiState.uiEvent) {
            is LoginUiEvent.NavigateToHome -> {
                viewModel.eventConsumed()
                onNavigateHome()
            }

            is LoginUiEvent.ShowError -> {
                viewModel.eventConsumed()

                // TODO: Show snackbar
            }

            is LoginUiEvent.OpenUrl -> {
                // Sign-up is a plain web page, not an OAuth callback: no shared session or token
                // hand-back, just a page the user must be able to get back out of. That's
                // InAppBrowser, not the auth-callback launcher. Consume only after the open is
                // issued, never before.
                InAppBrowser.open(uiEvent.url)
                viewModel.eventConsumed()
            }

            is LoginUiEvent.OpenAuthUrl -> {
                viewModel.eventConsumed()
                // Open the auth window (platform-specific). Sets up the callback listener on
                // success; on a blocked web popup, surfaces the "Continue" button instead.
                openAuth(uiEvent.url)
            }

            null -> {}
        }
    }

    LoginUi(
        uiState = uiState,
        pendingAuthUrl = pendingAuthUrl,
        onContinueAuth = { pendingAuthUrl?.let { openAuth(it) } },
        onAction = viewModel::onAction
    )
}
