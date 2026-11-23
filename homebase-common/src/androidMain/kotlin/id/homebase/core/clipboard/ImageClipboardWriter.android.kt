package id.homebase.core.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import id.homebase.api.ActivityProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

actual val canCopyImageToClipboard: Boolean = true

private val writeLock = Mutex()

// A fresh name per copy: the content URI is the clipboard's identity, and a receiver or the system
// clipboard preview that caches by URI would paste the previous image from a reused one.
internal actual suspend fun writePngToClipboard(png: suspend () -> ByteArray) {
    val bytes = png()
    val context = ActivityProvider.requireApplicationContext()
    val file = File(context.cacheDir, "clipboard/copied_image_${System.currentTimeMillis()}.png")
    // Locked so a second copy cannot delete this file between its write and setPrimaryClip.
    writeLock.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.listFiles()?.forEach { it.delete() }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Homebase", uri))
    }
}
