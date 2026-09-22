package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.input.key.KeyEvent

@Composable
actual fun keyboardHeightAsState(): State<Int> {
    TODO("Not yet implemented")
}

// UIPress, which is all a KeyEvent carries here, says nothing about marked text.
actual fun KeyEvent.isImeComposing(): Boolean = false
