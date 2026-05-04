package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.feed_error_retry
import id.homebase.resources.feed_error_title
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import org.jetbrains.compose.resources.stringResource

@Composable
fun FeedScreen(viewModel: FeedViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionsChecked by viewModel.feedExtendPermissionViewModel.permissionsChecked.collectAsStateWithLifecycle()
    val permissionsGranted by viewModel.feedExtendPermissionViewModel.permissionsGranted.collectAsStateWithLifecycle()
    val isSystemDark = isSystemInDarkTheme()

    LaunchedEffect(isSystemDark) {
        viewModel.updateSystemDarkMode(isSystemDark)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.feedExtendPermissionViewModel.recheckPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        viewModel.reloadEvent.collect { reloadKey++ }
    }

    ExtendPermissionDialog(viewModel = viewModel.feedExtendPermissionViewModel)

    FeedContent(
        uiState = uiState,
        permissionsChecked = permissionsChecked,
        permissionsGranted = permissionsGranted,
        onAction = viewModel::onAction,
        reloadKey = reloadKey,
    )
}

@Composable
private fun FeedContent(
    uiState: FeedUiState,
    permissionsChecked: Boolean,
    permissionsGranted: Boolean,
    onAction: (FeedUiAction) -> Unit,
    reloadKey: Int = 0,
) {
    when {
        uiState.error != null -> {
            FeedErrorView(
                errorMessage = uiState.error,
                onRetry = { onAction(FeedUiAction.RetryClicked) },
            )
        }

        uiState.credentialsReady && permissionsChecked && permissionsGranted -> {
            FeedWebView(
                url = uiState.feedUrl,
                injectionScript = uiState.injectionScript,
                isLoading = uiState.isLoading,
                onAction = onAction,
                reloadKey = reloadKey,
            )
        }

        else -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun FeedWebView(
    url: String,
    injectionScript: String?,
    isLoading: Boolean,
    onAction: (FeedUiAction) -> Unit,
    reloadKey: Int = 0,
) {
    val webViewState = rememberWebViewState(url)
    val webViewNavigator = rememberWebViewNavigator()
    val uriHandler = getUriHandler()
    val feedHost = remember(url) { extractHost(url) }
    var lastInjectedScript by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        if (reloadKey > 0) {
            webViewNavigator.loadUrl(url)
        }
    }

    // On first load completion: inject credentials into localStorage, then reload.
    // evaluateJavaScript is async (callback-based, not suspend), so we reload
    // in the callback to ensure localStorage is populated before the page reloads.
    LaunchedEffect(webViewState.isLoading, injectionScript) {
        if (!webViewState.isLoading && injectionScript != null && injectionScript != lastInjectedScript) {
            lastInjectedScript = injectionScript
            webViewNavigator.evaluateJavaScript(injectionScript) {
                // Navigate explicitly rather than reload(): the SPA may have
                // client-side-redirected to /owner/login before our injection ran,
                // and reload() would reload the login page instead of /apps/feed.
                webViewNavigator.loadUrl(url)
            }
        }
    }

    LaunchedEffect(webViewState.isLoading) {
        if (webViewState.isLoading) {
            onAction(FeedUiAction.PageStarted)
        } else if (lastInjectedScript != null) {
            onAction(FeedUiAction.PageFinished)
        }
    }

    LaunchedEffect(webViewState.lastLoadedUrl) {
        val lastUrl = webViewState.lastLoadedUrl ?: return@LaunchedEffect
        if (lastUrl != url && extractHost(lastUrl) != feedHost) {
            webViewNavigator.stopLoading()
            webViewNavigator.navigateBack()
            uriHandler.openUrl(lastUrl)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        PlatformWebView(
            state = webViewState,
            navigator = webViewNavigator,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FeedErrorView(
    errorMessage: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(MR.string.feed_error_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(MR.string.feed_error_retry))
        }
    }
}

private fun extractHost(url: String): String? {
    val protocolEnd = url.indexOf("//")
    if (protocolEnd < 0) return null
    val hostStart = protocolEnd + 2
    val hostEnd = url.indexOfAny(charArrayOf('/', ':', '?', '#'), startIndex = hostStart)
    return if (hostEnd >= 0) url.substring(hostStart, hostEnd).lowercase()
    else url.substring(hostStart).lowercase()
}
