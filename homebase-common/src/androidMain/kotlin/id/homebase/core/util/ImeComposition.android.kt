package id.homebase.core.util

import androidx.compose.ui.input.key.KeyEvent

// A soft keyboard composes over the InputConnection and emits no key event at all, and
// android.view.KeyEvent carries no composition flag for the hardware-keyboard case.
actual fun KeyEvent.isImeComposing(): Boolean = false
