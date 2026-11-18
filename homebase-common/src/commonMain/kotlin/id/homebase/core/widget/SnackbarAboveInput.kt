package id.homebase.core.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import kotlin.math.roundToInt

@Stable
internal class SnackbarClearance {
    val inputs = mutableStateMapOf<Any, LayoutCoordinates>()

    // Bumped whenever an input is placed; read only while placing the host, so a moving input re-places it without
    // recomposing anything.
    var moves by mutableIntStateOf(0)
}

internal val LocalSnackbarClearance = staticCompositionLocalOf<SnackbarClearance?> { null }

/** The snackbar host [SnackbarAboveInput] shows, for content inside it that has snackbars of its own to show. */
val LocalSnackbarAboveInputHost = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * [content] with the one snackbar host for [hostState], kept above any bottom input inside it marked
 * [keepSnackbarsAbove] (a message composer) that it overlaps, so a snackbar never covers what the person types in.
 * With no such input on screen it sits at the bottom, above the keyboard and the navigation bar. Content shows its own
 * snackbars through [LocalSnackbarAboveInputHost] rather than a host of its own, so they queue instead of stacking.
 */
@Composable
fun SnackbarAboveInput(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val clearance = remember { SnackbarClearance() }
    val box = remember { BoxPlacement() }
    CompositionLocalProvider(LocalSnackbarClearance provides clearance, LocalSnackbarAboveInputHost provides hostState) {
        Box(modifier.fillMaxSize().onPlaced { box.coordinates = it }) {
            content()
            SnackbarHost(
                hostState,
                Modifier.align(Alignment.BottomCenter)
                    .then(if (clearance.inputs.isEmpty()) Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)) else Modifier)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            clearance.moves
                            val lift = box.coordinates?.let { liftAbove(it, clearance.inputs.values, placeable.width, placeable.height) } ?: 0
                            placeable.place(0, -lift)
                        }
                    },
            )
        }
    }
}

private class BoxPlacement {
    var coordinates: LayoutCoordinates? = null
}

// How far to raise a host at the bottom centre of [box] so it clears the highest input it overlaps, in [box]'s own
// space, and never past the top of [box].
private fun liftAbove(box: LayoutCoordinates, inputs: Collection<LayoutCoordinates>, width: Int, height: Int): Int {
    if (!box.isAttached) return 0
    val hostLeft = (box.size.width - width) / 2f
    val hostRight = hostLeft + width
    val top = inputs.filter { it.isAttached }.mapNotNull { input ->
        val origin = box.localPositionOf(input, Offset.Zero)
        val overlaps = origin.x < hostRight && origin.x + input.size.width > hostLeft
        origin.y.takeIf { overlaps }
    }.minOrNull() ?: return 0
    return (box.size.height - top).roundToInt().coerceIn(0, (box.size.height - height).coerceAtLeast(0))
}

/** While on screen inside [SnackbarAboveInput], its snackbars stay above this. */
fun Modifier.keepSnackbarsAbove(): Modifier = this then KeepSnackbarsAboveElement

private data object KeepSnackbarsAboveElement : ModifierNodeElement<KeepSnackbarsAboveNode>() {
    override fun create() = KeepSnackbarsAboveNode()
    override fun update(node: KeepSnackbarsAboveNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "keepSnackbarsAbove"
    }
}

private class KeepSnackbarsAboveNode : Modifier.Node(), CompositionLocalConsumerModifierNode, GlobalPositionAwareModifierNode {
    private var clearance: SnackbarClearance? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val clearance = clearance ?: currentValueOf(LocalSnackbarClearance)?.also { clearance = it } ?: return
        clearance.inputs[this] = coordinates
        clearance.moves++
    }

    override fun onDetach() {
        clearance?.inputs?.remove(this)
        clearance = null
    }
}
