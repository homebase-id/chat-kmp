package id.homebase.core.util

import androidx.compose.ui.dom.domEventOrNull
import androidx.compose.ui.input.key.KeyEvent

/**
 * The browser flags the keydown that commits a candidate, and it arrives before compositionend.
 * Compose drops those itself on the hidden-input path, but its canvas listener has no such filter.
 */
actual fun KeyEvent.isImeComposing(): Boolean = domEventOrNull?.isComposing == true
