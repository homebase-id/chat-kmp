package id.homebase.core.clipboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Android's `item` builder has no `enabled` param, so gate by only adding the item when enabled.
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.pasteImageContextMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = this.appendTextContextMenuComponents {
    if (enabled) {
        item(key = "paste_image", label = label) {
            onClick()
            close()
        }
    }
}
