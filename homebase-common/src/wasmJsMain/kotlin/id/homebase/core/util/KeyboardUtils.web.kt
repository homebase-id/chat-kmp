package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.dom.domEventOrNull
import androidx.compose.ui.input.key.KeyEvent

@Composable
actual fun keyboardHeightAsState(): State<Int> = remember { mutableStateOf(0) }

// Compose drops composing keydowns on its hidden-input path, but its canvas listener does not.
actual fun KeyEvent.isImeComposing(): Boolean = domEventOrNull?.isComposing == true
