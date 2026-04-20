package id.homebase.core.clipboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.content.consume
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun clipboardImageReceiverModifier(onImagePasted: (ByteArray) -> Unit): Modifier {
    val context = LocalContext.current
    val callback = remember(onImagePasted) {
        { transferableContent: androidx.compose.foundation.content.TransferableContent ->
            if (!transferableContent.hasMediaType(MediaType.Image)) {
                transferableContent
            } else {
                transferableContent.consume { item ->
                    val uri = item.uri
                    if (uri != null) {
                        val bytes = context.contentResolver
                            .openInputStream(uri)
                            ?.use { it.readBytes() }
                        if (bytes != null) {
                            onImagePasted(bytes)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
        }
    }
    return Modifier.contentReceiver(callback)
}
