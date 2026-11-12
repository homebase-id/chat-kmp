package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * Wraps [content] so images committed from the soft keyboard (GIFs, stickers) reach [onImageReceived] as bytes,
 * capped at 20 MB (larger images are dropped, never truncated). A null [onImageReceived] turns it off.
 * Only Android has such a keyboard feature; iOS, desktop and web just render [content].
 */
@Composable
expect fun KeyboardImageReceiver(
    onImageReceived: ((ByteArray) -> Unit)?,
    content: @Composable () -> Unit,
)

internal val keyboardImageMimeTypes = arrayOf("image/gif", "image/png", "image/webp", "image/jpeg")

private const val MAX_KEYBOARD_IMAGE_BYTES = 20L * 1024 * 1024

internal fun acceptsKeyboardImage(mimeTypes: List<String>): Boolean =
    mimeTypes.any { it.lowercase() in keyboardImageMimeTypes }

// Over the cap returns null rather than a truncated (corrupt) image.
internal fun readKeyboardImage(source: Source, maxBytes: Long = MAX_KEYBOARD_IMAGE_BYTES): ByteArray? =
    if (source.request(maxBytes + 1)) null else source.readByteArray().takeIf { it.isNotEmpty() }
