package id.homebase.core.ui.screens.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.WebViewState

// NOTE: The kdroidfilter `composenativewebview` dependency has no wasmJs
// artifact, so this file's imports will fail when `wasmJs { browser() }` is
// enabled. Step 9 of the wasm pre-flight plan decouples that dependency from
// commonMain and refactors this expect to avoid the kdroidfilter types.
// Until then this placeholder records intent only.
@Composable
actual fun PlatformWebView(
    state: WebViewState,
    navigator: WebViewNavigator,
    modifier: Modifier,
) {
    error("PlatformWebView is not yet implemented on wasm — see step 9 of the wasm pre-flight plan.")
}
