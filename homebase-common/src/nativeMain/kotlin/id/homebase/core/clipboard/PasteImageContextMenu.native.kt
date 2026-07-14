package id.homebase.core.clipboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// iOS uses the skiko item() (same as jvm/web); readClipboardImage()'s iOS actual reads UIPasteboard.
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.pasteImageContextMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = this.appendTextContextMenuComponents {
    item(key = "paste_image", label = label, enabled = enabled) {
        onClick()
        close()
    }
}
