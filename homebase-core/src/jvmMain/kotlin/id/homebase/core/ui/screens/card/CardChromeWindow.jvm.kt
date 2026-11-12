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
import java.awt.Dialog
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.RootPaneContainer
import kotlin.math.roundToInt

internal actual val chromeFloatsOverCard: Boolean = true

// Room for a tooltip, which can't draw outside the chrome's own window; taken only while one shows.
private val TOOLTIP_ROOM_X = 16.dp
private val TOOLTIP_ROOM_Y = 48.dp
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
    val bounds = area ?: return
    val constraints = room ?: return
    val roomX = if (tooltipShown) TOOLTIP_ROOM_X.value.roundToInt() else 0
    val roomY = if (tooltipShown) TOOLTIP_ROOM_Y.value.roundToInt() else 0
    DialogWindow(
        visible = entered && !covered,
        create = {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                isUndecorated = true
                isTransparent = true
                isResizable = false
                focusableWindowState = false
                setSize(1, 1)
            }
        },
        dispose = ComposeDialog::dispose,
        update = { dialog ->
            moves
            val scale = owner.graphicsConfiguration.defaultTransform.scaleX
            val origin = owner.rootPane.contentPane.locationOnScreen
            dialog.setBounds(
                origin.x + (bounds.left / scale).roundToInt() - roomX,
                origin.y + (bounds.top / scale).roundToInt() - roomY,
                maxOf(size.width.value.roundToInt(), 1) + roomX * 2,
                maxOf(size.height.value.roundToInt(), 1) + roomY * 2,
            )
        },
    ) {
        // Its overlays sit above the card, so they don't send it away; a tooltip only widens this window.
        CompositionLocalProvider(LocalOpenOverlays provides tooltips) {
            val windowDensity = LocalDensity.current
            // Measured against the slot's room, not this window's size, so the window can start at a pixel and grow to fit.
            Layout(content = { Box { content() } }) { measurables, _ ->
                val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
                val measuredSize = with(windowDensity) { DpSize(placeable.width.toDp(), placeable.height.toDp()) }
                if (measuredSize != size) size = measuredSize
                val x = with(windowDensity) { roomX.dp.roundToPx() }
                val y = with(windowDensity) { roomY.dp.roundToPx() }
                layout(placeable.width + x * 2, placeable.height + y * 2) { placeable.place(x, y) }
            }
        }
    }
}
