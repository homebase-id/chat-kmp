package id.homebase.core.util

import androidx.compose.ui.input.key.KeyEvent

/**
 * Nothing to read — neither AWT nor Compose exposes a per-key "the input method is composing".
 * The platform settles it first: on macOS the native AWT view never turns the Return that commits
 * a candidate into a Java KeyEvent, and elsewhere Component.dispatchEventImpl returns as soon as
 * the input context consumes the key, before the KeyListener Compose registers.
 */
actual fun KeyEvent.isImeComposing(): Boolean = false
