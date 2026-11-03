package id.homebase.core.clipboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// PasteKey puts this in UIKit's own paste: slot, which reads the pasteboard without iOS's
// "Allow Paste" alert. Ancestors append after the field's built-in Paste, and the last PasteKey wins.
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.pasteContextMenuItem(label: String, onPaste: () -> Unit): Modifier =
    appendTextContextMenuComponents {
        if (clipboardMayHoldImage()) {
            item(key = TextContextMenuKeys.PasteKey, label = label) {
                onPaste()
                close()
            }
        }
    }
