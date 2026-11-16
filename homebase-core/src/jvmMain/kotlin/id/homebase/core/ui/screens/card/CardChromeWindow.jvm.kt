package id.homebase.core.ui.screens.card

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import id.homebase.core.ui.navigation.LocalPaneEntered
import id.homebase.core.widget.LocalOpenOverlays
import id.homebase.core.widget.OpenOverlays
import java.awt.AWTEvent
import java.awt.Dialog
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

internal actual val chromeFloatsOverCard: Boolean = true

// Room for a tooltip, which can't draw outside the chrome's own window; taken only while one shows or the pointer is over it.
private const val TOOLTIP_ROOM_X = 16
private const val TOOLTIP_ROOM_Y = 48
private val Everywhere = Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)

/**
 * wry's webview is a native view over the window's Compose layer (WryWebViewPanel hosts it in a heavyweight Canvas),
 * so nothing in this window's Compose tree draws over it. The chrome floats in a transparent window of its own, sized
 * to the chrome itself so the card takes every click around it. The slot here is sized from what that window measures.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun AboveCard(modifier: Modifier, content: @Composable () -> Unit) {
    val owner = LocalAwtWindow.current
    if (owner !is RootPaneContainer) return InlineAboveCard(modifier, content)
    val density = LocalDensity.current
    var area by remember { mutableStateOf<Rect?>(null) }
    var room by remember { mutableStateOf<Constraints?>(null) }
    var size by remember { mutableStateOf(DpSize.Zero) }
    Layout(modifier.onGloballyPositioned { area = it.boundsInWindow() }) { _, constraints ->
        if (room != constraints) room = constraints
        val (w, h) = with(density) { size.width.roundToPx() to size.height.roundToPx() }
        layout(constraints.constrainWidth(w), constraints.constrainHeight(h)) {}
    }
    val overlays = LocalOpenOverlays.current
    val covered by remember(overlays) { derivedStateOf { area?.let { overlays?.covers(it) } == true } }
    val entered by LocalPaneEntered.current
    val tooltips = remember { OpenOverlays() }
    val tooltipShown by remember { derivedStateOf { tooltips.covers(Everywhere) } }
    // A menu can't draw outside the chrome's own window either, and needs far more room: the window spans the content pane while one is open.
    val menuShown by remember { derivedStateOf { tooltips.coversWindow() } }
    // Moving the owner doesn't re-lay out Compose, so the chrome window follows it itself.
    var moves by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val moved = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) {
                moves++
            }
        }
        owner.addComponentListener(moved)
        onDispose { owner.removeComponentListener(moved) }
    }
    // AWT floats owned windows over their owner only when it gains focus, so one shown after that sits at normal level
    // and the owner's next click buries it; this keeps the chrome floating while the owner has focus.
    var ownerFocused by remember { mutableStateOf(owner.isFocused) }
    DisposableEffect(owner) {
        val focus = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) {
                ownerFocused = true
            }

            override fun windowLostFocus(e: WindowEvent) {
                ownerFocused = false
            }
        }
        owner.addWindowFocusListener(focus)
        onDispose { owner.removeWindowFocusListener(focus) }
    }
    // Resizing a window under the pointer sends it an exit, which hides the tooltip that grew it; the pointer, not the tooltip, holds the room.
    val hover = remember { PointerOver() }
    DisposableEffect(hover) {
        Toolkit.getDefaultToolkit().addAWTEventListener(hover, AWTEvent.MOUSE_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(hover) }
    }
    val bounds = area ?: return
    val constraints = room ?: return
    val scale = owner.graphicsConfiguration.defaultTransform.scaleX
    val left = (bounds.left / scale).roundToInt()
    val top = (bounds.top / scale).roundToInt()
    val tooltipRoom = tooltipShown || hover.inside
    val offsetX = if (menuShown) left else if (tooltipRoom) TOOLTIP_ROOM_X else 0
    val offsetY = if (menuShown) top else if (tooltipRoom) TOOLTIP_ROOM_Y else 0
    DialogWindow(
        visible = entered && !covered,
        create = {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                isUndecorated = true
                isTransparent = true
                isResizable = false
                focusableWindowState = false
                // macOS shadows the drawn pixels of a transparent window, outlining the chrome over the card.
                rootPane.putClientProperty("Window.shadow", false)
                setSize(1, 1)
                hover.window = this
            }
        },
        // macOS fades a closing window out over whatever the page has already switched to; a transparent one goes at once.
        dispose = { dialog ->
            dialog.opacity = 0f
            dialog.dispose()
        },
        update = { it.isAlwaysOnTop = ownerFocused },
    ) {
        // Its overlays sit above the card, so they don't send it away; they only grow this window.
        CompositionLocalProvider(LocalOpenOverlays provides tooltips) {
            val window = checkNotNull(LocalAwtWindow.current)
            val windowDensity = LocalDensity.current
            // Measured against the slot's room, not this window's size, so the window can start at a pixel and grow to fit.
            // Bounds are set in the pass that places the content: set a frame apart, the chrome jumps from under the pointer.
            Layout(content = { Box { content() } }) { measurables, _ ->
                moves
                val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
                val measuredSize = with(windowDensity) { DpSize(placeable.width.toDp(), placeable.height.toDp()) }
                if (measuredSize != size) size = measuredSize
                val pane = owner.rootPane.contentPane
                val origin = pane.locationOnScreen
                if (menuShown) {
                    window.setBounds(origin.x, origin.y, pane.width, pane.height)
                } else {
                    window.setBounds(
                        origin.x + left - offsetX,
                        origin.y + top - offsetY,
                        maxOf(measuredSize.width.value.roundToInt(), 1) + offsetX * 2,
                        maxOf(measuredSize.height.value.roundToInt(), 1) + offsetY * 2,
                    )
                }
                val x = with(windowDensity) { offsetX.dp.roundToPx() }
                val y = with(windowDensity) { offsetY.dp.roundToPx() }
                layout(placeable.width + x * 2, placeable.height + y * 2) { placeable.place(x, y) }
            }
        }
    }
}

private class PointerOver : AWTEventListener {
    var window: Window? = null
    var inside by mutableStateOf(false)
        private set

    override fun eventDispatched(event: AWTEvent) {
        val window = window ?: return
        if (event.id != MouseEvent.MOUSE_ENTERED && event.id != MouseEvent.MOUSE_EXITED) return
        if (SwingUtilities.getWindowAncestor((event as MouseEvent).component) !== window) return
        inside = MouseInfo.getPointerInfo()?.location?.let(window.bounds::contains) == true
    }
}
