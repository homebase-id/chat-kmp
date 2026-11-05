package id.homebase.core.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement

inline fun Modifier.noRippleClickable(crossinline onClick: () -> Unit): Modifier = composed {
    clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() }
    ) {
        onClick()
    }
}

inline fun Modifier.ifTrue(predicate: Boolean, builder: () -> Modifier) =
    then(if (predicate) builder() else Modifier)

inline fun <T : Any> Modifier.ifNotNull(value: T?, builder: (T) -> Modifier): Modifier =
    then(if (value != null) builder(value) else Modifier)

fun Modifier.conditional(condition : Boolean, modifier : Modifier.() -> Modifier) : Modifier {
    return if (condition) {
        then(modifier(Modifier))
    } else {
        this
    }
}

/** East-west resize cursor for drag-to-resize splitters; no-op where there is no mouse. */
expect fun Modifier.horizontalResizeCursor(): Modifier

/**
 * Runs [onPress] on a right-click and consumes it, unless a child (e.g. the text selection menu
 * or a nested [onSecondaryPress]) consumed it first.
 */
fun Modifier.onSecondaryPress(onPress: () -> Unit): Modifier = this then SecondaryPressElement(onPress)

// A node, so a fresh [onPress] each recomposition swaps the callback instead of restarting the pointer loop.
private data class SecondaryPressElement(val onPress: () -> Unit) : ModifierNodeElement<SecondaryPressNode>() {
    override fun create() = SecondaryPressNode(onPress)
    override fun update(node: SecondaryPressNode) {
        node.onPress = onPress
    }
}

private class SecondaryPressNode(var onPress: () -> Unit) : DelegatingNode() {
    init {
        delegate(SuspendingPointerInputModifierNode {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed &&
                        event.changes.none { it.isConsumed }
                    ) {
                        onPress()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        })
    }
}
