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
import co.touchlab.kermit.Logger

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
                        try {
                            // TODO(perf): Stream the URI directly to a temp file instead of
                            // allocating the full image as a ByteArray here. Today this round-trips:
                            // URI → byte[] → callback → VM.writeBytesToTempFile(bytes) → PlatformFile,
                            // costing one ~5–10 MB allocation per paste. Fixing it requires changing
                            // the (ByteArray) -> Unit callback contract in clipboardImageReceiverModifier
                            // (expect + 4 actuals), the AttachClipboardImage UiAction, and the
                            // onPasteImage hook in MessageInputBar / ConversationContent — touched in
                            // too many spots for the marginal one-off-per-paste win, deferred for now.
                            // When fixing: have the modifier copy the URI to a temp file via
                            // FileOperationsProvider and pass the resulting path. The URI is bound to
                            // the Compose contentReceiver scope and may be invalid by the time the VM
                            // coroutine runs.
                            val bytes = context.contentResolver
                                .openInputStream(uri)
                                ?.use { it.readBytes() }
                            if (bytes != null) {
                                onImagePasted(bytes)
                                true
                            } else {
                                Logger.w(tag = "ClipboardImageReceiver") { "Failed to read bytes from URI: $uri" }
                                false
                            }
                        } catch (e: Exception) {
                            Logger.e(throwable = e, tag = "ClipboardImageReceiver") { "Error reading pasted image" }
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
