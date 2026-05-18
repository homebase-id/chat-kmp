package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

// No real WebView on wasmJs — kdroidfilter `composenativewebview` has no
// wasmJs artifact. Step 9b ships a no-op handle so FeedScreen compiles for
// the wasmJs target; an actual browser embed (iframe, web component) and a
// user-facing "feed unavailable on web" string resource can come in a
// follow-up if/when the Feed UX matters on web.

@Composable
actual fun rememberFeedWebView(
    url: String,
    interceptor: (FeedWebRequest) -> FeedWebRequestDecision,
): FeedWebView = remember(url) { NoopFeedWebView }

@Composable
actual fun PlatformFeedWebView(
    webView: FeedWebView,
    modifier: Modifier,
) {
    // Empty placeholder surface — see file-level comment for rationale.
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

private object NoopFeedWebView : FeedWebView {
    override val state: FeedWebViewState = object : FeedWebViewState {
        override val isLoading: Boolean = false
        override val lastLoadedUrl: String? = null
        override val mainFrameErrors: List<FeedWebError> = emptyList()
    }
    override val controller: FeedWebViewController = object : FeedWebViewController {
        override fun loadUrl(url: String) {}
        override fun evaluateJavaScript(script: String, onResult: ((String?) -> Unit)?) {
            onResult?.invoke(null)
        }
        override fun stopLoading() {}
        override fun navigateBack() {}
    }
}
