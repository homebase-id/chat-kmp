package id.homebase.core.clipboard

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.core.view.inputmethod.EditorInfoCompat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asSource
import kotlinx.io.buffered

private const val TAG = "KeyboardImageReceiver"

// RichTextEditor is the legacy BasicTextField(TextFieldValue), whose InputConnection ignores
// Modifier.contentReceiver; only a composable wrapper, not a Modifier, can intercept its IME session.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun KeyboardImageReceiver(
    onImageReceived: ((ByteArray) -> Unit)?,
    content: @Composable () -> Unit,
) {
    val contentResolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val currentOnImageReceived by rememberUpdatedState(onImageReceived)
    val interceptor = remember(contentResolver, scope) {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(
                request: PlatformTextInputMethodRequest,
                nextHandler: PlatformTextInputSession,
            ): Nothing = nextHandler.startInputMethod(object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    if (currentOnImageReceived == null) return connection
                    EditorInfoCompat.setContentMimeTypes(outAttributes, keyboardImageMimeTypes)
                    return object : InputConnectionWrapper(connection, false) {
                        override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean =
                            scope.receiveKeyboardImage(contentResolver, info, flags) { bytes ->
                                currentOnImageReceived?.invoke(bytes)
                            } || super.commitContent(info, flags, opts)
                    }
                }
            })
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

private fun CoroutineScope.receiveKeyboardImage(
    contentResolver: ContentResolver,
    info: InputContentInfo,
    flags: Int,
    onImageReceived: (ByteArray) -> Unit,
): Boolean {
    val description = info.description
    if (!acceptsKeyboardImage(List(description.mimeTypeCount) { description.getMimeType(it) })) return false
    val granted = (flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
    if (granted) {
        try {
            info.requestPermission()
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) { "Keyboard image permission failed: ${info.contentUri}" }
            return false
        }
    }
    launch {
        try {
            withContext(Dispatchers.IO) { contentResolver.loadKeyboardImage(info.contentUri) }
                ?.let(onImageReceived)
        } finally {
            if (granted) info.releasePermission()
        }
    }
    return true
}

private fun ContentResolver.loadKeyboardImage(uri: Uri): ByteArray? {
    val bytes = try {
        openInputStream(uri)?.asSource()?.buffered()?.use { readKeyboardImage(it) }
    } catch (e: Exception) {
        Logger.w(throwable = e, tag = TAG) { "Failed to read keyboard image: $uri" }
        return null
    }
    if (bytes == null) Logger.w(tag = TAG) { "Dropped keyboard image (unreadable, empty or over the cap): $uri" }
    return bytes
}
