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
    // iOS WKWebView has localStorage enabled by default
    WebView(
        state = state,
        modifier = modifier,
        navigator = navigator,
    )
}
