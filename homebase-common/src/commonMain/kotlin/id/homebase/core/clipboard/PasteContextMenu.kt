package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One Paste in the text context menu that also takes images: replaces the built-in Paste (or, on
 * iOS, takes its slot) while [clipboardMayHoldImage], running [onPaste], which reads [readClipboard].
 * Identical on Android, desktop and web (one shared actual); iOS has its own.
 */
@Composable
expect fun Modifier.pasteContextMenuItem(label: String, onPaste: () -> Unit): Modifier
