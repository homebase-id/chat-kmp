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

actual fun createCardHost(odinId: String): CardHost = WebCardHost(odinId)

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
internal class WebCardHost(odinId: String) : CardHostBase(cardPageUrl(odinId, CardPageHost.FRAME)) {
    private val origin = cardOrigin(odinId)
    val frame: JsAny = createCardFrame()
    private val listener: JsAny = addCardMessageListener(frame, origin) { message ->
        guardJsCallback("CardHost") { onBridgeMessage(message) }
    }

    init {
        addCardFrameLoadListener(frame) { guardJsCallback("CardHost") { onMainFrameFinished() } }
        loadPage()
    }

    override fun loadUrl(url: String) = setCardFrameSrc(frame, url)

    override fun send(command: CardCommand) = when (command) {
        is CardCommand.Render -> postCardCommand(frame, "render", command.payload.toJson(), origin)
        CardCommand.ExportPng -> postCardCommand(frame, "exportPng", null, origin)
    }

    override fun release() {
        removeCardMessageListener(listener)
        removeCardFrame(frame)
    }
}

// No top navigation or popups, which would unload the memory-only app; allow-same-origin keeps the site's real origin for its storage and our origin check.
private fun createCardFrame(): JsAny = js(
    """{
        var f = document.createElement('iframe');
        f.style.position = 'fixed';
        f.style.border = '0';
        f.style.background = 'transparent';
        f.style.zIndex = '2147483000';
        f.style.display = 'none';
        f.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        return f;
    }"""
)

// Appended only once src is set, so the only load event is the card page's.
private fun setCardFrameSrc(frame: JsAny, url: String): Unit = js(
    "{ frame.src = url; if (!frame.parentNode) document.body.appendChild(frame); }"
)

private fun addCardFrameLoadListener(frame: JsAny, onLoad: () -> Unit): Unit = js(
    "{ frame.addEventListener('load', function () { onLoad(); }); }"
)

private fun postCardCommand(frame: JsAny, command: String, requestJson: String?, origin: String): Unit = js(
    """{
        var w = frame.contentWindow;
        if (!w) return;
        var message = { homebaseCardCommand: command };
        if (requestJson !== null) message.request = JSON.parse(requestJson);
        w.postMessage(message, origin);
    }"""
)

private fun addCardMessageListener(frame: JsAny, origin: String, onMessage: (String) -> Unit): JsAny = js(
    """{
        var listener = function (e) {
            if (e.source === frame.contentWindow && e.origin === origin && e.data && e.data.homebaseCard) {
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
