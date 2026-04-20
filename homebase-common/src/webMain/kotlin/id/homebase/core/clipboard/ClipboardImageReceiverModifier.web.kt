package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun clipboardImageReceiverModifier(onImagePasted: (ByteArray) -> Unit): Modifier {
    return Modifier
}
