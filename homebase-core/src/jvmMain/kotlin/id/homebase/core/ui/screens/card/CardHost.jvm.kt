package id.homebase.core.ui.screens.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import io.github.kdroidfilter.webview.wry.Rgba
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import java.awt.Container
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual fun createCardHost(): CardHost = DesktopCardHost()

@Composable
actual fun CardHostView(host: CardHost, modifier: Modifier) {
    val cardHost = host as DesktopCardHost
    key(cardHost) {
        val slot = remember { CardSlot(cardHost) }
        DisposableEffect(slot) {
            onDispose { cardHost.leave(slot) }
        }
        SwingPanel(factory = { slot }, modifier = modifier)
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
internal class DesktopCardHost : CardHostBase() {
    val panel = WryWebViewPanel(
        initialUrl = BLANK_URL,
        initScript = bridgeShim("window.ipc.postMessage"),
        backgroundColor = Rgba(0u, 0u, 0u, 0u),
    )
    private var hadWebView = false
    private var shown = false
    private val awaitedReplies = mutableSetOf<KClass<out CardEvent>>()

    // wry only exposes a drain call for window.ipc messages, so poll while the card shows or a reply is due.
    private val polling = MutableStateFlow(false)

    init {
        // With no listener the panel rejects every navigation, loadHtml's own included.
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

    override fun render(payload: CardPayload) {
        awaitReply(CardEvent.Ready::class)
        super.render(payload)
    }

    override fun exportPng() {
        awaitReply(CardEvent.Png::class)
        super.exportPng()
    }

    override fun loadHtml(html: String) {
        awaitReply(CardEvent.Loaded::class)
        panel.loadHtml(html)
    }

    override fun evaluateNow(js: String) = panel.evaluateJavaScript(js) {}

    override fun release() {
        val parent = panel.parent ?: return
        parent.remove(panel)
        parent.repaint()
    }

    private fun park() {
        val layeredPane = parkingWindow()?.layeredPane ?: return
        moveInto(layeredPane)
        val size = panel.size.takeIf { it.width > 0 && it.height > 0 } ?: layeredPane.size
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

    private fun pollWebView() {
        val hasWebView = panel.isReady()
        // A destroyed webview comes back blank, so queue the page for the next one.
        if (hadWebView && !hasWebView) loadPage()
        hadWebView = hasWebView
        panel.drainIpcMessages().forEach(::onBridgeMessage)
    }

    private fun allowNavigation(url: String): Boolean {
        if (url == BLANK_URL) return true
        scope.launch { onPageError("blocked navigation to $url") }
        return false
    }

    private companion object {
        const val BLANK_URL = "about:blank"

        const val IPC_POLL_MS = 16L
        const val PARK_MARGIN = 100
    }
}
