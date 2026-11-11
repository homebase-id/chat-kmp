package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A modifier that delivers an image pasted into the focused field to [onImagePasted] as its raw bytes.
 * Android handles it through the content receiver; iOS, desktop and web return [Modifier] unchanged and never call it.
 * Runs on the main thread; a URI whose bytes cannot be read is dropped without a call.
 */
@Composable
expect fun clipboardImageReceiverModifier(onImagePasted: (ByteArray) -> Unit): Modifier
