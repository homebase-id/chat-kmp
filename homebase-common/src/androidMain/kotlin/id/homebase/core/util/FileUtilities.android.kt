package id.homebase.core.util

import android.content.ComponentName
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import id.homebase.api.file.AndroidFileOperationsProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

private const val TAG = "getUriHandler"

@Composable
actual fun getUriHandler(): FileSystemHandler {
    val context = LocalContext.current

    return remember {
        val albumSaver = AndroidAlbumSaver(context)
        val fileOps = AndroidFileOperationsProvider(context.applicationContext)
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
                            // Homebase lists itself for documents: a chooser started from here must not offer it back.
                            val own = context.packageManager.queryIntentActivities(intent, 0)
                                .filter { it.activityInfo.packageName == context.packageName }
                                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                            if (own.isNotEmpty()) putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, own.toTypedArray())
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

            override fun shareFile(file: Path, onError: (Throwable) -> Unit) =
                shareFile(file, text = null, onError = onError)

            override fun shareFile(file: Path, text: String?, onError: (Throwable) -> Unit) {
                try {
                    val javaFile = File(file.toString())
                    val authority = "${context.packageName}.fileprovider"
                    val uri = FileProvider.getUriForFile(context, authority, javaFile)
                    val mimeType = detectContentTypeFromExtensionOrHint(javaFile.name)

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        // Caption travels as EXTRA_TEXT. Many gallery/social
                        // targets drop it on image shares, but messaging/email
                        // targets honor it — best-effort by design.
                        if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
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
                deleteAfter: Boolean,
                onSuccess: (String) -> Unit,
                onError: (Throwable) -> Unit,
            ) = saveThroughAlbum(
                albumSaver, fileOps, file, suggestedName, deleteAfter, onSuccess, onError,
                // API 28 without WRITE_EXTERNAL_STORAGE has no album to write to, so the share sheet offers the file,
                // which the receiving app reads later.
                onRefused = { source, name, _, mayGiveAway ->
                    val moved = if (mayGiveAway) moveToShareOutbound(fileOps, source, name) else null
                    shareFile(Path(moved ?: source), onError)
                    moved == null
                },
            )

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

/** [source] moved, under [name], into its own folder in `share_outbound/`, swept when the app returns; null if it can't be. */
private suspend fun moveToShareOutbound(fileOps: FileOperationsProvider, source: String, name: String): String? =
    withContext(Dispatchers.IO) {
        val target = File(fileOps.createShareOutboundPath(""), name)
        if (target.parentFile?.mkdirs() == true && File(source).renameTo(target)) {
            target.path
        } else {
            Logger.w(tag = TAG) { "Couldn't move the file to share into share_outbound; sharing it where it is" }
            null
        }
    }
