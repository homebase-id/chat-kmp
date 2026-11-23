package id.homebase.core.image

import androidx.compose.ui.graphics.ImageBitmap
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.Base64Bytes
import org.jetbrains.compose.resources.decodeToImageBitmap

fun EmbeddedThumb.decodeBitmap(): ImageBitmap? = content?.decodeImageBitmap()

internal fun Base64Bytes.decodeImageBitmap(): ImageBitmap? = try {
    bytes.decodeToImageBitmap()
} catch (_: Exception) {
    null
}
