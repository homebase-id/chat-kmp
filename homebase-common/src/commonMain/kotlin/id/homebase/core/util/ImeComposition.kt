package id.homebase.core.util

import androidx.compose.ui.input.key.KeyEvent

/**
 * True while an input method owns this key press — the Enter that confirms a CJK candidate, or a
 * dead-key accent. Composing text is not the composer's to act on.
 */
expect fun KeyEvent.isImeComposing(): Boolean
