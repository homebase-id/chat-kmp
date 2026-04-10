package id.homebase.core.util

import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import java.io.File

private const val TAG = "getUriHandler"

@Composable
actual fun getUriHandler(): FileSystemHandler {
    val context = LocalContext.current

    return remember {
        object : FileSystemHandler {
            override fun openUrl(url: String, onError: (Throwable) -> Unit) {
                val customTabsIntent =
                    CustomTabsIntent.Builder().setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
                        .setShowTitle(true).build()

                customTabsIntent.launchUrl(context, url.toUri())
            }

            override fun editFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {
                TODO("Not yet implemented")
            }

            override fun openFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {
                try {
                    val javaFile = File(file.toString())
                    val authority = "${context.packageName}.fileprovider"
                    val uri = FileProvider.getUriForFile(context, authority, javaFile)
                    val mimeType = detectContentTypeFromExtensionOrHint(javaFile.name)

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    val finalIntent = if (showChooser) {
                        Intent.createChooser(intent, null).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    } else {
                        intent
                    }

                    context.startActivity(finalIntent)
                } catch (e: Exception) {
                    Logger.e(throwable = e, tag = TAG) { "Failed to open file: ${e.message}" }
                    onError(e)
                }
            }

            override fun openFileBrowser(file: Path, onError: (Throwable) -> Unit) {
                TODO("Not yet implemented")
            }

            override fun shareFile(file: Path, onError: (Throwable) -> Unit) {
                try {
                    val javaFile = File(file.toString())
                    val authority = "${context.packageName}.fileprovider"
                    val uri = FileProvider.getUriForFile(context, authority, javaFile)
                    val mimeType = detectContentTypeFromExtensionOrHint(javaFile.name)

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(intent, null)
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Logger.e(throwable = e, tag = TAG) { "Failed to share file: ${e.message}" }
                    onError(e)
                }
            }

            override fun saveFile(
                file: Path,
                suggestedName: String,
                onSuccess: (String) -> Unit,
                onError: (Throwable) -> Unit,
            ) {
                try {
                    val sourceFile = File(file.toString())
                    val mimeType = detectContentTypeFromExtensionOrHint(suggestedName)

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
                            put(MediaStore.MediaColumns.DISPLAY_NAME, suggestedName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }

                        val resolver = context.contentResolver
                        val uri = resolver.insert(collection, values)
                            ?: throw Exception("Failed to create MediaStore entry")

                        resolver.openOutputStream(uri)?.use { outputStream ->
                            sourceFile.inputStream().use { it.copyTo(outputStream) }
                        } ?: throw Exception("Failed to open output stream")

                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)

                        onSuccess(directory)
                    } else {
                        // API 27-28 — legacy external storage
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                        if (!hasPermission) {
                            onError(Exception("Storage permission required to save files"))
                            return
                        }

                        val directory = when {
                            mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
                            mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                            else -> Environment.DIRECTORY_DOWNLOADS
                        }

                        @Suppress("DEPRECATION")
                        val destDir = Environment.getExternalStoragePublicDirectory(directory)
                        destDir.mkdirs()
                        val destFile = File(destDir, suggestedName)
                        sourceFile.copyTo(destFile, overwrite = true)

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
            }

            override fun shareText(text: String, onError: (Throwable) -> Unit) {
                try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    val chooser = Intent.createChooser(intent, null)
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Logger.e(throwable = e, tag = TAG) { "Failed to share text: ${e.message}" }
                    onError(e)
                }
            }

            override fun openAppStore(onError: (Throwable) -> Unit) {
                TODO("Not yet implemented")
            }
        }
    }
}
