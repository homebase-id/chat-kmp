package id.homebase.core.clipboard

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private object PasteWithImageKey

@Composable
actual fun Modifier.pasteContextMenuItem(label: String, onPaste: () -> Unit): Modifier = this
    .filterTextContextMenuComponents { it.key != TextContextMenuKeys.PasteKey || !clipboardMayHoldImage() }
    .appendTextContextMenuComponents {
        if (clipboardMayHoldImage()) {
            item(key = PasteWithImageKey, label = label) {
                onPaste()
                close()
            }
        }
    }
