package id.homebase.core.clipboard

import android.content.ClipboardManager
import android.content.Context
import id.homebase.api.ActivityProvider

actual fun getImageFromClipboard(): ByteArray? {
    // Image paste from clipboard on Android is handled natively by the OS keyboard.
    // This function is primarily used for desktop Ctrl+V paste interception.
    return null
}

actual suspend fun readClipboard(): ClipboardContents {
    val context = ActivityProvider.requireApplicationContext()
    val clip = context.clipboardManager()?.primaryClip
    if (clip == null || clip.itemCount == 0) return ClipboardContents(image = null, text = null)
    val mimeTypes = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
    val item = clip.getItemAt(0)
    val image = item.uri?.let { uri ->
        selectImageBytes(mimeTypes) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
    }
    return ClipboardContents(image = image, text = item.text?.toString())
}

actual fun clipboardMayHoldImage(): Boolean {
    val description = ActivityProvider.requireApplicationContext().clipboardManager()?.primaryClipDescription
        ?: return false
    return (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }
}

private fun Context.clipboardManager(): ClipboardManager? =
    getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
