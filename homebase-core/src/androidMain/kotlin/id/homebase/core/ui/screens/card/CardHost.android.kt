package id.homebase.core.ui.screens.card

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools

actual fun createCardHost(): CardHost =
    AndroidCardHost(KoinPlatformTools.defaultContext().get().get<Context>())

@Composable
actual fun CardHostView(host: CardHost, modifier: Modifier) {
    val cardHost = host as AndroidCardHost
    key(cardHost.webView) {
        AndroidView(
            factory = { activityContext -> cardHost.attach(activityContext) },
            modifier = modifier,
            onRelease = { cardHost.detach() },
        )
    }
}

internal class AndroidCardHost(context: Context) : CardHostBase() {
    private val appContext = context.applicationContext

    // Lets the pre-created WebView borrow the showing Activity, then drop it so it doesn't leak.
    private val contextWrapper = MutableContextWrapper(appContext)
    private var attachments = 0

    var webView by mutableStateOf(newWebView())
        private set

    init {
        loadPage()
    }

    fun attach(activityContext: Context): WebView {
        attachments++
        contextWrapper.baseContext = activityContext
        // A same-frame swap can run the new factory before the old holder releases the view.
        (webView.parent as? ViewGroup)?.removeView(webView)
        return webView
    }

    fun detach() {
        attachments--
        if (attachments == 0) contextWrapper.baseContext = appContext
    }

    override fun loadHtml(html: String) {
        webView.loadDataWithBaseURL(CARD_BASE_URL, html, "text/html", "utf-8", null)
    }

    override fun evaluateNow(js: String) {
        webView.evaluateJavascript(js, null)
    }

    override fun release() {
        webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun newWebView() = WebView(contextWrapper).apply {
        // Without MATCH_PARENT every vh/dvh unit resolves to 0.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = CardWebViewClient()
        addJavascriptInterface(JsBridge(), JS_BRIDGE_NAME)
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun post(json: String) {
            scope.launch { onBridgeMessage(json) }
        }
    }

    private inner class CardWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            onPageError("blocked navigation to ${request.url}")
            return true
        }

        // Returning false (the default) takes the whole app down with the renderer.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onPageError("renderer gone crashed=${detail.didCrash()}")
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            webView = newWebView()
            loadPage()
            return true
        }
    }

    private companion object {
        const val JS_BRIDGE_NAME = "homebaseCardHost"
    }
}
