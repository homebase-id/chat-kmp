package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Adds a "Paste image" entry to the text context menu of the composable it modifies.
// onClick fires on tap; the caller reads the clipboard (readClipboardImage) itself.
@Composable
expect fun Modifier.pasteImageContextMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier
