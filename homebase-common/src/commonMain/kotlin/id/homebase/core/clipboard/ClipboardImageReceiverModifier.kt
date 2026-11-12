package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A modifier that delivers an image pasted into the focused field to [onImagePasted] as its raw bytes.
 * Android handles it through the content receiver; iOS, desktop and web return [Modifier] unchanged and never call it.
 * The bytes are read off the main thread and delivered back on the composition's context, so it never blocks
 * the UI thread; a URI whose bytes cannot be read is dropped without a call.
 */
@Composable
expect fun clipboardImageReceiverModifier(onImagePasted: (ByteArray) -> Unit): Modifier
