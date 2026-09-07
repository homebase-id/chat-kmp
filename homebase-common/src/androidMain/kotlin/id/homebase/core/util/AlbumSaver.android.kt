package id.homebase.core.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import java.io.File

private const val TAG = "AndroidAlbumSaver"

class AndroidAlbumSaver(private val context: Context) : AlbumSaver {

    override fun save(
        file: Path,
        suggestedName: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val safeName = suggestedName
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')

        // kotlinx.io.files.Path collapses `content://` to `content:/`; restore it.
        val rawPath = file.toString()
        val sourceUri = when {
            rawPath.startsWith("content://") -> rawPath.toUri()
            rawPath.startsWith("content:/") ->
                "content://${rawPath.removePrefix("content:/")}".toUri()

            else -> null
        }

        Thread {
            try {
                fun openSource(): java.io.InputStream = if (sourceUri != null) {
                    context.contentResolver.openInputStream(sourceUri)
                        ?: throw Exception("Failed to open source URI: $sourceUri")
                } else {
                    File(rawPath).inputStream()
                }
                val mimeType = detectContentTypeFromExtensionOrHint(safeName)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // API 29+ — use MediaStore (no permission needed)
                    val (collection, directory) = when {
                        mimeType.startsWith("image/") ->
                            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                                    Environment.DIRECTORY_PICTURES

                        mimeType.startsWith("video/") ->
                            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                                    Environment.DIRECTORY_MOVIES

                        else ->
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                                    Environment.DIRECTORY_DOWNLOADS
                    }

                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(collection, values)
                        ?: throw Exception("Failed to create MediaStore entry")

                    try {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            openSource().use { it.copyTo(outputStream) }
                        } ?: throw Exception("Failed to open output stream")

                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        // Clean up orphaned MediaStore entry on failure
                        resolver.delete(uri, null, null)
                        throw e
                    }

                    onSuccess(directory)
                } else {
                    // API 27-28 — legacy external storage
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        onError(AlbumAccessDeniedException("WRITE_EXTERNAL_STORAGE not granted"))
                        return@Thread
                    }

                    val directory = when {
                        mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
                        mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_DOWNLOADS
                    }

                    @Suppress("DEPRECATION")
                    val destDir = Environment.getExternalStoragePublicDirectory(directory)
                    destDir.mkdirs()
                    val destFile = File(destDir, safeName)
                    destFile.outputStream().use { out ->
                        openSource().use { it.copyTo(out) }
                    }

                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destFile.absolutePath),
                        arrayOf(mimeType),
                        null,
                    )

                    onSuccess(directory)
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Failed to save file: ${e.message}" }
                onError(e)
            }
        }.start()
    }
}
