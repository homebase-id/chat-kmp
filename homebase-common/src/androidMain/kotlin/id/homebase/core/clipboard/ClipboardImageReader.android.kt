package id.homebase.core.clipboard

import android.content.ClipboardManager
import android.content.Context
import id.homebase.api.ActivityProvider

actual fun getImageFromClipboard(): ByteArray? {
    // Image paste from clipboard on Android is handled natively by the OS keyboard.
    // This function is primarily used for desktop Ctrl+V paste interception.
    return null
}

actual suspend fun readClipboardImage(): ByteArray? {
    val context = ActivityProvider.requireApplicationContext()
    val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboardManager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val mimeTypes = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
    val uri = clip.getItemAt(0).uri ?: return null
    return selectImageBytes(mimeTypes) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }
}
