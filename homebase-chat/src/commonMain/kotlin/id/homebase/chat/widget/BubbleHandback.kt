package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize

@Stable
internal class BubbleHandback(val layer: GraphicsLayer) {
    var active by mutableStateOf(false)
    var hidden by mutableStateOf(false)
    var size by mutableStateOf(IntSize.Zero)
        private set

    private var coordinates: LayoutCoordinates? = null
    private var lastPosition = Offset.Zero

    fun onPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        size = coordinates.size
    }

    // Screen space, not window space: a Popup's window can be offset from the host window on Android.
    fun rowPosition(): Offset {
        val c = coordinates
        if (c != null && c.isAttached) lastPosition = c.positionOnScreen()
        return lastPosition
    }
}

@Composable
internal fun rememberBubbleHandback(): BubbleHandback {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BubbleHandback(layer) }
}

// Records only while the overlay is up, so ordinary rows pay nothing. Hidden = recorded but not drawn.
internal fun Modifier.handbackSource(handback: BubbleHandback): Modifier =
    onGloballyPositioned(handback::onPositioned).drawWithContent {
        if (handback.active) {
            handback.layer.record { this@drawWithContent.drawContent() }
            if (!handback.hidden) drawLayer(handback.layer)
        } else {
            drawContent()
        }
    }

@Composable
internal fun AnimatedVisibilityScope.HandbackBubble(handback: BubbleHandback) {
    val progress by transition.animateFloat(
        transitionSpec = { if (targetState == EnterExitState.Visible) signalFly() else signalHide() },
        label = "handbackFly",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val scale by transition.animateFloat(
        transitionSpec = { if (targetState == EnterExitState.Visible) signalFly() else signalHide() },
        label = "handbackScale",
    ) { if (it == EnterExitState.PreEnter) 0.95f else 1f }
    var slotPosition by remember { mutableStateOf(Offset.Zero) }

    SideEffect { handback.hidden = true }
    DisposableEffect(handback) { onDispose { handback.hidden = false } }

    val size = handback.size
    Box(
        Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(
                    Constraints.fixed(size.width, size.height),
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .onGloballyPositioned { slotPosition = it.positionOnScreen() }
            .graphicsLayer {
                val remaining = handback.rowPosition() - slotPosition
                translationX = remaining.x * (1f - progress)
                translationY = remaining.y * (1f - progress)
                scaleX = scale
                scaleY = scale
            }
            .drawBehind { drawLayer(handback.layer) },
    )
}
