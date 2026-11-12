package id.homebase.core.clipboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.content.consume
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.ContentResolver
import android.net.Uri
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun clipboardImageReceiverModifier(onImagePasted: (ByteArray) -> Unit): Modifier {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnImagePasted by rememberUpdatedState(onImagePasted)
    val callback = remember(context, scope) {
        { transferableContent: androidx.compose.foundation.content.TransferableContent ->
            if (!transferableContent.hasMediaType(MediaType.Image)) {
                transferableContent
            } else {
                transferableContent.consume { item ->
                    val uri = item.uri
                    if (uri != null) {
                        scope.launchPastedImageRead(
                            readDispatcher = Dispatchers.IO,
                            read = { context.contentResolver.readPastedImage(uri) },
                            onImagePasted = { currentOnImagePasted(it) },
                        )
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
    return Modifier.contentReceiver(callback)
}

private fun ContentResolver.readPastedImage(uri: Uri): ByteArray? = try {
    openInputStream(uri)?.use { it.readBytes() }
} catch (e: Exception) {
    Logger.e(throwable = e, tag = "ClipboardImageReceiver") { "Error reading pasted image: $uri" }
    null
}
