package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent

@Composable
actual fun keyboardHeightAsState(): State<Int> = remember { mutableStateOf(0) }

// Believed unnecessary: AWT's input context should consume the committing key before Compose's
// KeyListener sees it. Unverified against Pinyin, Kotoeri or ibus/fcitx.
actual fun KeyEvent.isImeComposing(): Boolean = false
