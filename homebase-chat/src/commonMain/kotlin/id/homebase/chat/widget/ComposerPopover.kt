package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

private val POPOVER_MAX_HEIGHT = 440.dp
private val POPOVER_MIN_HEIGHT = 200.dp
private val POPOVER_ANCHOR_GAP = 8.dp
private val POPOVER_WINDOW_MARGIN = 8.dp

internal class PopoverAnchor {
    var coordinates: LayoutCoordinates? = null

    fun topInWindow(): Float? = coordinates?.takeIf { it.isAttached }?.positionInWindow()?.y
}

internal fun Modifier.popoverAnchor(anchor: PopoverAnchor): Modifier = onPlaced { anchor.coordinates = it }

// Compose it inside the anchor: a Popup anchors to its parent layout.
@Composable
internal fun ComposerPopover(
    anchor: PopoverAnchor,
    alignToEnd: Boolean,
    onDismissRequest: () -> Unit,
    width: Dp? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider = remember(density, alignToEnd) {
        with(density) {
            AboveBubblePositionProvider(
                gapPx = POPOVER_ANCHOR_GAP.roundToPx(),
                alignToEnd = alignToEnd,
                windowMarginPx = POPOVER_WINDOW_MARGIN.roundToPx(),
                flipBelow = false,
            )
        }
    }
    // Read outside the Popup: on Android its content sits in a separate window with its own insets.
    val reservedAbove = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() +
        POPOVER_ANCHOR_GAP + POPOVER_WINDOW_MARGIN
    val growsFromRight = alignToEnd == (LocalLayoutDirection.current == LayoutDirection.Ltr)
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = scaleIn(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                initialScale = 0.7f,
                transformOrigin = TransformOrigin(if (growsFromRight) 1f else 0f, 1f),
            ) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            Surface(
                modifier = Modifier
                    .then(if (width != null) Modifier.width(width) else Modifier)
                    .layout { measurable, constraints ->
                        val spaceAbove = anchor.topInWindow()?.minus(reservedAbove.toPx())
                            ?: POPOVER_MAX_HEIGHT.toPx()
                        val maxHeight = spaceAbove
                            .coerceIn(POPOVER_MIN_HEIGHT.toPx(), POPOVER_MAX_HEIGHT.toPx())
                            .roundToInt()
                        val placeable = measurable.measure(constraints.constrain(Constraints(maxHeight = maxHeight)))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                shape = MaterialTheme.shapes.extraLarge,
                color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation,
                shadowElevation = MenuDefaults.ShadowElevation,
                content = content,
            )
        }
    }
}
