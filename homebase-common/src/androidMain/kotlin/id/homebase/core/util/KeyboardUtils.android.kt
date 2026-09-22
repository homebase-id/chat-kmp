package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@Composable
actual fun keyboardHeightAsState(): State<Int> {
    val view = LocalView.current
    val keyboardHeight = remember { mutableIntStateOf(0) }

    DisposableEffect(view) {
        val listener = OnApplyWindowInsetsListener { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            keyboardHeight.intValue = imeInsets.bottom
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(view, listener)
        ViewCompat.requestApplyInsets(view)
        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
        }
    }

    return keyboardHeight
}

// A soft keyboard composes over the InputConnection and emits no key event at all, and
// android.view.KeyEvent carries no composition flag for the hardware-keyboard case.
actual fun KeyEvent.isImeComposing(): Boolean = false
