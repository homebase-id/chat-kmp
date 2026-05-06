package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import co.touchlab.kermit.Logger
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
import androidx.compose.runtime.snapshotFlow
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
import id.homebase.resources.feed_loading
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onNavigateToChat: () -> Unit = {},
) {
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

    LaunchedEffect(Unit) {
        viewModel.feedExtendPermissionViewModel.navigateAwayRequest.collect {
            onNavigateToChat()
        }
    }

    ExtendPermissionDialog(
        viewModel = viewModel.feedExtendPermissionViewModel,
        onCancel = onNavigateToChat,
    )

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
            FeedLoadingOverlay()
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
    val uriHandler = getUriHandler()
    val feedHost = remember(url) { extractHost(url) }
    var lastInjectedScript by remember { mutableStateOf<String?>(null) }
    val interceptor = remember(feedHost) {
        FeedRequestInterceptor(
            allowedHost = feedHost,
            isFeedReady = { lastInjectedScript != null },
            openExternally = { externalUrl: String -> uriHandler.openUrl(externalUrl) },
        )
    }
    val webViewNavigator = rememberWebViewNavigator(requestInterceptor = interceptor)
    var isReady by remember { mutableStateOf(false) }
    var minDisplayElapsed by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        if (reloadKey > 0) {
            webViewNavigator.loadUrl(url)
        }
    }

    LaunchedEffect(Unit) {
        delay(700)
        minDisplayElapsed = true
    }

    LaunchedEffect(Unit) {
        delay(8_000)
        if (!isReady) isReady = true
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
            isReady = true
        }
    }

    LaunchedEffect(webViewState.lastLoadedUrl) {
        val lastUrl = webViewState.lastLoadedUrl ?: return@LaunchedEffect
//        Logger.i(tag = "FeedWebView") {
//            "nav: $lastUrl (loading=${webViewState.isLoading}, injected=${lastInjectedScript != null})"
//        }
        if (lastUrl != url && extractHost(lastUrl) != feedHost) {
            webViewNavigator.stopLoading()
            webViewNavigator.navigateBack()
            uriHandler.openUrl(lastUrl)
        }
    }

    // Surface main-frame load errors during the initial auth handoff. Once the
    // feed is up (lastInjectedScript != null), let the WebView display its own
    // error UI for transient failures rather than yanking the user out.
    LaunchedEffect(Unit) {
        snapshotFlow { webViewState.errorsForCurrentRequest.toList() }
            .collect { errors ->
                if (lastInjectedScript != null) return@collect
                val mainFrameError = errors.firstOrNull { it.isFromMainFrame } ?: return@collect
                onAction(FeedUiAction.PageError(mainFrameError.description))
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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

        if (!isReady || !minDisplayElapsed) {
            FeedLoadingOverlay()
        }
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

@Composable
private fun FeedLoadingOverlay() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(MR.string.feed_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class FeedRequestInterceptor(
    private val allowedHost: String?,
    private val isFeedReady: () -> Boolean,
    private val openExternally: (String) -> Unit,
) : RequestInterceptor {
    override fun onInterceptUrlRequest(
        request: WebRequest,
        navigator: WebViewNavigator,
    ): WebRequestInterceptResult {
        if (!request.isForMainFrame) return WebRequestInterceptResult.Allow

        val isSameHost = extractHost(request.url) == allowedHost
        val path = extractPath(request.url)
        val isFeedPath = path == "/apps/feed" || path?.startsWith("/apps/feed/") == true

        if (isSameHost && isFeedPath) return WebRequestInterceptResult.Allow

        // Until the auth handoff completes, silently reject — the SPA may try to
        // redirect to /owner/login before our injection lands. Once the feed is
        // up, treat any non-/apps/ navigation as a user-driven link.
        if (!isFeedReady()) {
//            Logger.i(tag = "FeedWebView") { "blocked pre-handoff nav → ${request.url}" }
            return WebRequestInterceptResult.Reject
        }

//        Logger.i(tag = "FeedWebView") {
//            val kind = if (isSameHost) "same-host non-apps" else "external"
//            "redirecting $kind → ${request.url}"
//        }
        openExternally(request.url)
        return WebRequestInterceptResult.Reject
    }
}

private fun extractPath(url: String): String? {
    val protocolEnd = url.indexOf("//")
    if (protocolEnd < 0) return null
    val pathStart = url.indexOf('/', startIndex = protocolEnd + 2)
    if (pathStart < 0) return "/"
    val pathEnd = url.indexOfAny(charArrayOf('?', '#'), startIndex = pathStart)
    return if (pathEnd >= 0) url.substring(pathStart, pathEnd) else url.substring(pathStart)
}

private fun extractHost(url: String): String? {
    val protocolEnd = url.indexOf("//")
    if (protocolEnd < 0) return null
    val hostStart = protocolEnd + 2
    val hostEnd = url.indexOfAny(charArrayOf('/', ':', '?', '#'), startIndex = hostStart)
    return if (hostEnd >= 0) url.substring(hostStart, hostEnd).lowercase()
    else url.substring(hostStart).lowercase()
}
