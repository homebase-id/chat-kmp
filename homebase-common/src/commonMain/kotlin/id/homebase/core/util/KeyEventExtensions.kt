package id.homebase.core.util

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key

// macOS can report Return as NumPadEnter, so Key.Enter alone misses it.
val KeyEvent.isEnter: Boolean get() = key == Key.Enter || key == Key.NumPadEnter
