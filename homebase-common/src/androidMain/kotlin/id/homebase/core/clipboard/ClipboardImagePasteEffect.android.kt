package id.homebase.core.clipboard

import androidx.compose.runtime.Composable

@Composable
actual fun ClipboardImagePasteEffect(enabled: Boolean, onImagePasted: (ByteArray) -> Unit) {
    // The key handler reads the clipboard directly on this platform.
}
