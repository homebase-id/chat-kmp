package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent

@Composable
actual fun keyboardHeightAsState(): State<Int> = remember { mutableStateOf(0) }

// UIPress, which is all a KeyEvent carries here, says nothing about marked text.
actual fun KeyEvent.isImeComposing(): Boolean = false
