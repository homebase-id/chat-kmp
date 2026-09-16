package id.homebase.core.util

import androidx.compose.ui.input.key.KeyEvent

// UIPress, which is all a KeyEvent carries here, says nothing about marked text.
actual fun KeyEvent.isImeComposing(): Boolean = false
