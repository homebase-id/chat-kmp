@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import id.homebase.api.browser.guardJsCallback
import id.homebase.core.util.showHtmlOverlay

actual fun createCardHost(): CardHost = WebCardHost()

@Composable
actual fun CardHostView(host: CardHost, modifier: Modifier) {
    val cardHost = host as WebCardHost
    val density = LocalDensity.current.density
    var bounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(cardHost, bounds) {
        val b = bounds ?: return@LaunchedEffect
        showHtmlOverlay(
            cardHost.frame,
            (b.left / density).toDouble(),
            (b.top / density).toDouble(),
            (b.width / density).toDouble(),
            (b.height / density).toDouble(),
        )
    }
    DisposableEffect(cardHost) {
        onDispose { hideCardFrame(cardHost.frame) }
    }
    Box(modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() })
}

// Canvas-rendered Compose can't host DOM, so the iframe floats over the view's bounds (see showHtmlOverlay).
internal class WebCardHost : CardHostBase() {
    val frame: JsAny = createCardFrame()
    private val listener: JsAny = addCardMessageListener(frame) { message ->
        guardJsCallback("CardHost") { onBridgeMessage(message) }
    }

    init {
        loadPage()
    }

    override fun loadHtml(html: String) = setCardFrameSrcdoc(frame, html)

    override fun evaluateNow(js: String) = evaluateInCardFrame(frame, js)

    override fun release() {
        removeCardMessageListener(listener)
        removeCardFrame(frame)
    }
}

// The sandbox blocks top navigation and popups, which would unload the memory-only app; same-origin keeps contentDocument reachable.
private fun createCardFrame(): JsAny = js(
    """{
        var f = document.createElement('iframe');
        f.style.position = 'fixed';
        f.style.border = '0';
        f.style.background = 'transparent';
        f.style.zIndex = '2147483000';
        f.style.display = 'none';
        f.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        document.body.appendChild(f);
        return f;
    }"""
)

private fun setCardFrameSrcdoc(frame: JsAny, html: String): Unit = js("{ frame.srcdoc = html; }")

// card.html's CSP allows 'unsafe-inline' but not 'unsafe-eval', so contentWindow.eval throws EvalError.
private fun evaluateInCardFrame(frame: JsAny, script: String): Unit = js(
    """{
        var doc = frame.contentDocument;
        if (!doc || !doc.documentElement) return;
        var s = doc.createElement('script');
        s.textContent = script;
        doc.documentElement.appendChild(s);
        s.remove();
    }"""
)

private fun addCardMessageListener(frame: JsAny, onMessage: (String) -> Unit): JsAny = js(
    """{
        var listener = function (e) {
            if (e.source === frame.contentWindow && e.data && e.data.homebaseCard) {
                onMessage(JSON.stringify(e.data.homebaseCard));
            }
        };
        window.addEventListener('message', listener);
        return listener;
    }"""
)

private fun removeCardMessageListener(listener: JsAny): Unit = js(
    "{ window.removeEventListener('message', listener); }"
)

private fun hideCardFrame(frame: JsAny): Unit = js("{ frame.style.display = 'none'; }")

private fun removeCardFrame(frame: JsAny): Unit = js(
    "{ if (frame.parentNode) frame.parentNode.removeChild(frame); }"
)
