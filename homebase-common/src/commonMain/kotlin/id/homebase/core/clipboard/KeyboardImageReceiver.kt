package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import kotlinx.io.Source
import kotlinx.io.readByteArray

@Composable
expect fun KeyboardImageReceiver(
    onImageReceived: ((ByteArray) -> Unit)?,
    content: @Composable () -> Unit,
)

internal val keyboardImageMimeTypes = arrayOf("image/gif", "image/png", "image/webp", "image/jpeg")

internal const val MAX_KEYBOARD_IMAGE_BYTES = 20L * 1024 * 1024

internal fun acceptsKeyboardImage(mimeTypes: List<String>): Boolean =
    mimeTypes.any { it.lowercase() in keyboardImageMimeTypes }

// Over the cap returns null rather than a truncated (corrupt) image.
internal fun readKeyboardImage(source: Source, maxBytes: Long = MAX_KEYBOARD_IMAGE_BYTES): ByteArray? =
    if (source.request(maxBytes + 1)) null else source.readByteArray().takeIf { it.isNotEmpty() }
