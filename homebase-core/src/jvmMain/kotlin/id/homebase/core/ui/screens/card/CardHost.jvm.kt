package id.homebase.core.ui.screens.card

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import io.github.kdroidfilter.webview.wry.Rgba
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import java.awt.Container
import java.awt.Dimension
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.util.concurrent.Executors
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual fun createCardHost(odinId: String): CardHost = DesktopCardHost(cardPageUrl(odinId))

@Composable
actual fun CardHostView(host: CardHost, modifier: Modifier, layoutWidth: Dp?, shape: Shape) {
    val cardHost = host as DesktopCardHost
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val size = remember { mutableStateOf(Size.Zero) }
    val radii by remember(shape, density, layoutDirection) {
        derivedStateOf { cssCornerRadii(shape, size.value, density, layoutDirection) }
    }
    // Every card frame sits on surfaceContainer; wry's view is opaque, so the page paints that outside its corners.
    val outside = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    LaunchedEffect(cardHost, radii, outside) { cardHost.roundCorners(radii, outside) }
    key(cardHost) {
        val slot = remember { CardSlot(cardHost) }
        DisposableEffect(slot) {
            onDispose { cardHost.leave(slot) }
        }
        SwingPanel(factory = { slot }, modifier = modifier.onSizeChanged { size.value = it.toSize() })
    }
}

// SwingPanel adds and removes this slot, never the panel itself, whose removeNotify would kill the webview.
internal class CardSlot(private val host: DesktopCardHost) : JPanel(null) {
    override fun addNotify() {
        super.addNotify()
        host.show(this)
    }

    override fun doLayout() {
        if (width > 0 && height > 0) components.forEach { it.setBounds(0, 0, width, height) }
    }
}

/**
 * wry destroys its native webview on removeNotify, so between views the panel is parked off-screen in
 * the window's layered pane (which also loads the page before any view exists) instead of removed.
 */
internal class DesktopCardHost(pageUrl: String) : CardHostBase(pageUrl) {
    val panel = WryWebViewPanel(
        initialUrl = pageUrl,
        initScript = bridgeShim("window.ipc.postMessage"),
        backgroundColor = Rgba(0u, 0u, 0u, 0u),
    )
    private var hadWebView = false
    private var sawLoading = false
    private var shown = false
    private var tileSized = false
    private var sizeBeforeTiles: Dimension? = null
    private var cornersScript = ""
    private val awaitedReplies = mutableSetOf<KClass<out CardEvent>>()

    // wry dispatch_syncs onto the AppKit main queue, which a main thread spinning AWT's run loop for an
    // invokeAndWait (accessibility, the screen menu bar) never drains: called on the EDT, the two wait on each other.
    private val webViewExecutor = Executors.newSingleThreadExecutor { Thread(it, "card-webview").apply { isDaemon = true } }
    private val webView = webViewExecutor.asCoroutineDispatcher()

    // wry only exposes a drain call for window.ipc messages, so poll while the card shows or a reply is due.
    private val polling = MutableStateFlow(false)

    init {
        // With no listener the panel rejects every navigation, loadUrl's own included.
        panel.addNavigateListener(::allowNavigation)
        // wry moves its native view only from doLayout, which AWT skips when a component merely moves.
        panel.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = panel.doLayout()
        })
        panel.addHierarchyBoundsListener(object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent) = panel.doLayout()
        })
        // Parked below the content pane, where setComponentZOrder's append puts it.
        JLayeredPane.putLayer(panel, Int.MIN_VALUE)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { event ->
                if (event is CardEvent.Error) awaitedReplies.clear() else awaitedReplies -= event::class
                updatePolling()
            }
        }
        scope.launch {
            while (isActive) {
                polling.first { it }
                pollWebView()
                delay(IPC_POLL_MS)
            }
        }
        loadPage()
        park()
    }

    fun show(slot: CardSlot) {
        if (!scope.isActive) return
        shown = true
        updatePolling()
        moveInto(slot)
        panel.setLocation(0, 0)
        slot.doLayout()
        panel.doLayout()
    }

    fun leave(slot: CardSlot) {
        if (panel.parent !== slot || !scope.isActive) return
        shown = false
        updatePolling()
        park()
    }

    // body becomes the scroller so its clip stays put; html, outside the clip, takes the frame's backdrop.
    fun roundCorners(radii: String?, outsideArgb: Int) {
        val outside = "rgb(${outsideArgb shr 16 and 0xFF},${outsideArgb shr 8 and 0xFF},${outsideArgb and 0xFF})"
        cornersScript = radii?.let {
            "(function(){var s=document.getElementById('hb-corners')||document.head.appendChild(document.createElement('style'));" +
                "s.id='hb-corners';s.textContent='html{background:$outside!important;height:100%;overflow:hidden}" +
                "body{height:100%;overflow-y:auto;clip-path:inset(0 round $it)}'})()"
        } ?: "(function(){var s=document.getElementById('hb-corners');if(s)s.remove()})()"
        applyCorners()
    }

    private fun applyCorners() {
        val script = cornersScript.ifEmpty { return }
        scope.launch(webView) { panel.evaluateJavaScript(script) {} }
    }

    // Back to the last view's size after, so a later export (sharing) gets the page that view showed.
    override fun sizeForTiles(on: Boolean) {
        tileSized = on
        if (panel.parent is CardSlot) return
        if (on) sizeBeforeTiles = panel.size else {
            sizeBeforeTiles?.let { panel.size = it }
            sizeBeforeTiles = null
        }
        park()
    }

    override fun loadUrl(url: String) {
        sawLoading = false
        awaitReply(CardEvent.Loaded::class)
        panel.loadUrl(url)
    }

    override fun send(command: CardCommand) {
        when (command) {
            is CardCommand.Render -> CardEvent.Ready::class
            CardCommand.ExportPng -> CardEvent.Png::class
            CardCommand.ProbeEdges -> CardEvent.Edges::class
            CardCommand.RequestPaint -> CardEvent.Painted::class
        }.let(::awaitReply)
        val script = command.script()
        scope.launch(webView) { panel.evaluateJavaScript(script) {} }
    }

    override fun release() {
        webViewExecutor.shutdown()
        val parent = panel.parent ?: return
        parent.remove(panel)
        parent.repaint()
    }

    private fun park() {
        val layeredPane = parkingWindow()?.layeredPane ?: return
        moveInto(layeredPane)
        // wry creates the native view at the window's top-left at the panel's size, moving it only on a 16ms timer,
        // so it is created at 1x1 and given the parking size once it exists.
        val size = when {
            !hadWebView -> Dimension(1, 1)
            tileSized -> TILE_SIZE
            panel.width > 1 && panel.height > 1 -> panel.size
            else -> layeredPane.size
        }
        panel.setBounds(-size.width - PARK_MARGIN, 0, size.width, size.height)
        panel.doLayout()
    }

    // Unlike add, setComponentZOrder skips removeNotify, but only within one window.
    private fun moveInto(container: Container) {
        val sameWindow = SwingUtilities.getWindowAncestor(panel) === SwingUtilities.getWindowAncestor(container)
        when {
            panel.parent === container -> Unit
            panel.parent != null && sameWindow -> container.setComponentZOrder(panel, container.componentCount)
            else -> container.add(panel)
        }
    }

    private fun parkingWindow(): RootPaneContainer? {
        (SwingUtilities.getWindowAncestor(panel) as? RootPaneContainer)?.let { return it }
        val showing = Window.getWindows().filter { it.isShowing && it is RootPaneContainer }
        return (showing.firstOrNull { it.isActive } ?: showing.firstOrNull()) as? RootPaneContainer
    }

    private fun awaitReply(reply: KClass<out CardEvent>) {
        awaitedReplies += reply
        updatePolling()
    }

    private fun updatePolling() {
        polling.value = shown || awaitedReplies.isNotEmpty()
    }

    private suspend fun pollWebView() {
        val trackLoad = CardEvent.Loaded::class in awaitedReplies
        val poll = withContext(webView) {
            val ready = panel.isReady()
            WebViewPoll(
                ready = ready,
                messages = panel.drainIpcMessages(),
                loading = ready && trackLoad && panel.isLoading(),
                url = if (ready && trackLoad) panel.getCurrentUrl() else null,
            )
        }
        // A destroyed webview's replacement starts a fresh page, which has to say `loaded` again.
        if (hadWebView && !poll.ready) loadPage()
        val created = !hadWebView && poll.ready
        hadWebView = poll.ready
        if (created && panel.parent !is CardSlot) park()
        poll.messages.forEach(::onBridgeMessage)
        if (poll.ready && CardEvent.Loaded::class in awaitedReplies) trackMainFrame(poll)
    }

    private class WebViewPoll(val ready: Boolean, val messages: List<String>, val loading: Boolean, val url: String?)

    // wry reports no page-load events; a load can also finish between two polls.
    private fun trackMainFrame(poll: WebViewPoll) {
        if (poll.loading) {
            sawLoading = true
        } else if (sawLoading || poll.url == pageUrl) {
            sawLoading = false
            applyCorners()
            onMainFrameFinished()
        }
    }

    private fun allowNavigation(url: String): Boolean {
        if (url == pageUrl) return true
        scope.launch { onPageError("blocked navigation to $url") }
        return false
    }

    private companion object {
        const val IPC_POLL_MS = 16L
        const val PARK_MARGIN = 100
        val TILE_SIZE = Dimension(CAPTURE_LAYOUT_WIDTH.value.toInt(), (CAPTURE_LAYOUT_WIDTH.value / CARD_THUMB_ASPECT).toInt())
    }
}
