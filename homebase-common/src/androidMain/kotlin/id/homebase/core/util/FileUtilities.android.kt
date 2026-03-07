package id.homebase.core.util

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
