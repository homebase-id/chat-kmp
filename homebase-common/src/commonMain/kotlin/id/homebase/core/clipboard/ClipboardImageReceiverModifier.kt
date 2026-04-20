package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun clipboardImageReceiverModifier(onImagePasted: (ByteArray) -> Unit): Modifier
