package id.homebase.core.util

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
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent(PointerEventPass.Final)
                if (down.changes.any { it.pressed && !it.previousPressed }) {
                    focusManager.clearFocus()
                }
            }
        }
    }
}