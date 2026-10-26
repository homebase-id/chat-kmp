package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Makes the text context menu (and Android's floating toolbar) offer one Paste that also takes
 * images. While [clipboardMayHoldImage] is false the built-in Paste is left alone, so a text paste
 * keeps the editor's own handling; otherwise the built-in is replaced by an item that runs [onPaste],
 * which reads the clipboard itself ([readClipboard]).
 */
@Composable
expect fun Modifier.pasteContextMenuItem(label: String, onPaste: () -> Unit): Modifier
