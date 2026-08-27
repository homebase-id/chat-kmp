package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.feed_unavailable_web
import org.jetbrains.compose.resources.stringResource

// No real WebView on wasmJs — kdroidfilter `composenativewebview` has no wasmJs
// artifact. The controller is a no-op so FeedScreen compiles for the wasmJs target;
// an actual browser embed (iframe, web component) can come in a follow-up if/when
// the Feed UX matters on web. Until then say so rather than rendering an empty
// surface the user reads as a broken screen.

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
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(MR.string.feed_unavailable_web),
            modifier = Modifier.padding(32.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
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
