package id.homebase.core.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import id.homebase.api.util.freeFileName
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val ALBUM_FOLDER = "Homebase"

class AndroidAlbumSaver(private val context: Context) : AlbumSaver {

    // MediaStore asks no permission and has no picker, so asked or not is the same here.
    override suspend fun target(name: String, asked: Boolean): AlbumTarget {
        val mimeType = detectContentTypeFromExtensionOrHint(name)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return legacyTarget(name, mimeType)
        // Non-cancellable: once inserted, the entry must reach the caller, whose discard removes it.
        val (uri, relativePath) = withContext(Dispatchers.IO + NonCancellable) { insertPending(name, mimeType) }
        return AlbumTarget.InPlace(
            path = uri.toString(),
            publish = { withContext(Dispatchers.IO) { publish(uri) }; relativePath },
            discard = { withContext(Dispatchers.IO) { context.contentResolver.delete(uri, null, null) } },
        )
    }

    private fun legacyTarget(name: String, mimeType: String): AlbumTarget {
        val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) throw AlbumAccessDeniedException("WRITE_EXTERNAL_STORAGE not granted")
        val directory = directoryFor(mimeType)
        @Suppress("DEPRECATION")
        val destDir = File(Environment.getExternalStoragePublicDirectory(directory), ALBUM_FOLDER).apply { mkdirs() }
        val dest = File(destDir, freeFileName(name) { File(destDir, it).exists() })
        return AlbumTarget.InPlace(
            path = dest.absolutePath,
            publish = {
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mimeType), null)
                "$directory/$ALBUM_FOLDER"
            },
            discard = { withContext(Dispatchers.IO) { dest.delete() } },
        )
    }

    /** A hidden entry under Pictures, Movies or Downloads by type, and its folder for the message. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertPending(safeName: String, mimeType: String): Pair<Uri, String> {
        val directory = directoryFor(mimeType)
        val collection = when (directory) {
            Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = "$directory/$ALBUM_FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values) ?: throw Exception("Failed to create MediaStore entry")
        return uri to relativePath
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publish(uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun directoryFor(mimeType: String) = when {
        mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
        mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
    }
}
