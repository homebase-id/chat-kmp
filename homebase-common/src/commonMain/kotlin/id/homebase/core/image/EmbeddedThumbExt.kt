package id.homebase.core.image

import androidx.compose.ui.graphics.ImageBitmap
import id.homebase.api.client.drives.upload.EmbeddedThumb
import org.jetbrains.compose.resources.decodeToImageBitmap

fun EmbeddedThumb.decodeBitmap(): ImageBitmap? {
    val raw = content ?: return null
    return try {
        raw.bytes.decodeToImageBitmap()
    } catch (_: Exception) {
        null
    }
}
