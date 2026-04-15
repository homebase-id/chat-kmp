package id.homebase.core.ui.screens.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.WebViewState

/**
 * Platform wrapper for the WebView composable.
 * On Android, this enables DOM storage (localStorage) which is disabled by default.
 * On other platforms, this delegates directly to the library's WebView.
 */
@Composable
expect fun PlatformWebView(
    state: WebViewState,
    navigator: WebViewNavigator,
    modifier: Modifier,
)
