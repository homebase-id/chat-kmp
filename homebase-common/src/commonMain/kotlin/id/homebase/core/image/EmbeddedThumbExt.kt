package id.homebase.core.image

import androidx.compose.ui.graphics.ImageBitmap
import id.homebase.api.client.drives.upload.EmbeddedThumb
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
fun EmbeddedThumb.decodeBitmap(): ImageBitmap? {
    val raw = content ?: return null
    return try {
        val cleaned = raw
            .removePrefix("data:image/png;base64,")
            .removePrefix("data:image/jpeg;base64,")
            .removePrefix("data:image/jpg;base64,")
        Base64.decode(cleaned).decodeToImageBitmap()
    } catch (_: Exception) {
        null
    }
}
