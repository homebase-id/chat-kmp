package id.homebase.core.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import id.homebase.api.ActivityProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// One fixed file: the clipboard only ever holds the latest copy, so overwriting it is safe.
internal actual suspend fun writePngToClipboard(png: ByteArray) {
    val context = ActivityProvider.requireApplicationContext()
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "clipboard").apply { mkdirs() }
            .resolve("copied_image.png").apply { writeBytes(png) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Homebase", uri))
}
