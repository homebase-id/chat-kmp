package id.homebase.core.ui.screens.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.WebViewState

@Composable
actual fun PlatformWebView(
    state: WebViewState,
    navigator: WebViewNavigator,
    modifier: Modifier,
) {
    WebView(
        state = state,
        modifier = modifier,
        navigator = navigator,
        onCreated = { nativeWebView ->
            // Enable DOM storage (localStorage) — disabled by default on Android WebView
            nativeWebView.settings.domStorageEnabled = true
        },
        onDispose = {},
    )
}
