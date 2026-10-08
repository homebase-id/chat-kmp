package id.homebase.core.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager

@Composable
fun keyboardAsState(): State<Boolean> {
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current

    return remember {
        derivedStateOf {
            imeInsets.getBottom(density) > 0
        }
    }
}

@Composable
expect fun keyboardHeightAsState(): State<Int>

// Only the browser reports composition per key; every other target returns a constant false.
expect fun KeyEvent.isImeComposing(): Boolean

fun Modifier.dismissKeyboardOnTap(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    this.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            // Initial pass: children's tap/drag handlers consume later, and a consumed tap must still count.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Mouse) return@awaitEachGesture
            withTimeoutOrNull(longPressMs) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                    if (event.changes.size > 1 || (change.position - down.position).getDistance() > slop) {
                        return@withTimeoutOrNull true
                    }
                    if (!change.pressed) {
                        focusManager.clearFocus()
                        return@withTimeoutOrNull true
                    }
                }
            } ?: focusManager.clearFocus()
        }
    }
}
