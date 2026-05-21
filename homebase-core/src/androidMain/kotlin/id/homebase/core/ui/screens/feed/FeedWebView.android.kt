package id.homebase.core.ui.screens.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.WebViewState
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState

@Composable
actual fun rememberFeedWebView(
    url: String,
    interceptor: (FeedWebRequest) -> FeedWebRequestDecision,
): FeedWebView {
    val state = rememberWebViewState(url)
    val nativeInterceptor = remember(interceptor) { interceptor.toRequestInterceptor() }
    val navigator = rememberWebViewNavigator(requestInterceptor = nativeInterceptor)
    return remember(state, navigator) { KdroidfilterFeedWebView(state, navigator) }
}

@Composable
actual fun PlatformFeedWebView(
    webView: FeedWebView,
    modifier: Modifier,
) {
    val handle = webView as KdroidfilterFeedWebView
    WebView(
        state = handle.nativeState,
        modifier = modifier,
        navigator = handle.nativeNavigator,
        onCreated = { nativeWebView ->
            // Enable DOM storage (localStorage) — disabled by default on Android WebView.
            nativeWebView.settings.domStorageEnabled = true
        },
        onDispose = {},
    )
}

/**
 * Bridges the kdroidfilter `composenativewebview` state/navigator pair to
 * the [FeedWebView] facade in commonMain. Property reads stay Compose-aware:
 * `state.isLoading` etc. read directly from the underlying [WebViewState]
 * (Compose `State`-backed) so reads inside a composition / snapshotFlow
 * correctly re-subscribe. (Same shape duplicated across the three
 * real-WebView platforms — see step 9b of the WASM pre-flight plan; no
 * common ancestor source set exists for Android/JVM/Native.)
 */
internal class KdroidfilterFeedWebView(
    val nativeState: WebViewState,
    val nativeNavigator: WebViewNavigator,
) : FeedWebView {
    override val state: FeedWebViewState = object : FeedWebViewState {
        override val isLoading: Boolean get() = nativeState.isLoading
        override val lastLoadedUrl: String? get() = nativeState.lastLoadedUrl
        override val mainFrameErrors: List<FeedWebError>
            get() = nativeState.errorsForCurrentRequest
                .filter { it.isFromMainFrame }
                .map { FeedWebError(it.description) }
    }
    override val controller: FeedWebViewController = object : FeedWebViewController {
        override fun loadUrl(url: String) = nativeNavigator.loadUrl(url)
        override fun evaluateJavaScript(script: String, onResult: ((String?) -> Unit)?) {
            if (onResult == null) nativeNavigator.evaluateJavaScript(script)
            else nativeNavigator.evaluateJavaScript(script, onResult)
        }
        override fun stopLoading() = nativeNavigator.stopLoading()
        override fun navigateBack() = nativeNavigator.navigateBack()
    }
}

internal fun ((FeedWebRequest) -> FeedWebRequestDecision).toRequestInterceptor(): RequestInterceptor =
    object : RequestInterceptor {
        override fun onInterceptUrlRequest(
            request: WebRequest,
            navigator: WebViewNavigator,
        ): WebRequestInterceptResult {
            val decision = this@toRequestInterceptor(
                FeedWebRequest(url = request.url, isForMainFrame = request.isForMainFrame)
            )
            return when (decision) {
                FeedWebRequestDecision.Allow -> WebRequestInterceptResult.Allow
                FeedWebRequestDecision.Reject -> WebRequestInterceptResult.Reject
            }
        }
    }
