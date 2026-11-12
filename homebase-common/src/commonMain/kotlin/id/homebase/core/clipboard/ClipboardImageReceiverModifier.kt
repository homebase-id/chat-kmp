package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Receives images pasted into the focused field. [onImagePasted] gets the image bytes and never blocks
 * the UI thread on any platform: the bytes are read off the main thread and delivered back on the
 * composition's context. Platforms without paste-receive support return [Modifier] unchanged.
 */
@Composable
expect fun clipboardImageReceiverModifier(onImagePasted: (ByteArray) -> Unit): Modifier
