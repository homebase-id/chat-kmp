package id.homebase.core.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

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

/** Runs [onPress] on a right-click, unless a child (e.g. the text selection menu) consumed it. */
fun Modifier.onSecondaryPress(onPress: () -> Unit): Modifier = pointerInput(onPress) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed &&
                event.changes.none { it.isConsumed }
            ) onPress()
        }
    }
}
