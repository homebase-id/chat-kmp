package id.homebase.core.ui.screens.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

/**
 * Compose-aware, read-only snapshot of the Feed WebView's state. Properties
 * are backed by Compose `State` on platforms with a real WebView, so reads
 * inside composition / snapshotFlow correctly re-subscribe.
 *
 * The platform facade lives here so commonMain code (FeedScreen) never
 * references the kdroidfilter `composenativewebview` types directly — that
 * library has no wasmJs artifact and must stay off the commonMain
 * dependency graph. See step 9b of the WASM pre-flight plan.
 */
@Stable
interface FeedWebViewState {
    val isLoading: Boolean
    val lastLoadedUrl: String?
    /**
     * Errors that affected the **main frame** of the current request. Sub-frame
     * errors (iframes, scripts) are filtered out at the facade boundary so
     * commonMain doesn't have to know what a sub-frame is.
     */
    val mainFrameErrors: List<FeedWebError>
}

@Immutable
data class FeedWebError(val description: String)

/** Imperative controls for the Feed WebView. */
@Stable
interface FeedWebViewController {
    fun loadUrl(url: String)
    fun evaluateJavaScript(script: String, onResult: ((String?) -> Unit)? = null)
    fun stopLoading()
    fun navigateBack()
}

/** Subset of a WebView request that the Feed's interceptor cares about. */
@Immutable
data class FeedWebRequest(val url: String, val isForMainFrame: Boolean)

sealed interface FeedWebRequestDecision {
    data object Allow : FeedWebRequestDecision
    data object Reject : FeedWebRequestDecision
}

/** Composite handle bundling state + controller; what FeedScreen sees. */
@Stable
interface FeedWebView {
    val state: FeedWebViewState
    val controller: FeedWebViewController
}

/**
 * Constructs the platform WebView state + controller for [url], wiring the
 * given [interceptor] for main-frame navigation decisions. On platforms
 * without a real WebView (wasmJs) returns a no-op handle.
 */
@Composable
expect fun rememberFeedWebView(
    url: String,
    interceptor: (FeedWebRequest) -> FeedWebRequestDecision,
): FeedWebView

/**
 * Renders the platform WebView bound to [webView]. Must be called with the
 * same [webView] instance returned by [rememberFeedWebView].
 */
@Composable
expect fun PlatformFeedWebView(
    webView: FeedWebView,
    modifier: Modifier,
)
