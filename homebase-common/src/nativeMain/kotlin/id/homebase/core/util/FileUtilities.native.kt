package id.homebase.core.util

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

@Composable
actual fun getUriHandler(): FileSystemHandler {
    return object : FileSystemHandler {
        override fun openUrl(url: String, onError: (Throwable) -> Unit) {
            val nsUrl = NSURL.URLWithString(url) ?: return
            UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, String>()) { success ->
                if (!success) {
                    onError(Exception("Failed to open URL: $url"))
                }
            }
        }

        override fun editFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }

        @OptIn(ExperimentalForeignApi::class)
        override fun openFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {
            try {
                val fileUrl = NSURL.fileURLWithPath(file.toString())
                val interactionController =
                    platform.UIKit.UIDocumentInteractionController.interactionControllerWithURL(
                        fileUrl
                    )
                val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController

                if (rootVC != null) {
                    val success = interactionController.presentOpenInMenuFromRect(
                        rect = rootVC.view.bounds, inView = rootVC.view, animated = true
                    )
                    if (!success) {
                        onError(Exception("Failed to present open in menu"))
                    }
                } else {
                    onError(Exception("Root View Controller not found"))
                }
            } catch (e: Exception) {
                onError(e)
            }
        }

        override fun openFileBrowser(file: Path, onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }

        @OptIn(ExperimentalForeignApi::class)
        override fun shareFile(file: Path, onError: (Throwable) -> Unit) =
            shareFile(file, text = null, onError = onError)

        @OptIn(ExperimentalForeignApi::class)
        override fun shareFile(file: Path, text: String?, onError: (Throwable) -> Unit) {
            try {
                val filePath = file.toString()
                if (!NSFileManager.defaultManager.fileExistsAtPath(filePath)) {
                    onError(Exception("Share file not found at $filePath"))
                    return
                }
                val fileUrl = NSURL.fileURLWithPath(filePath)
                // The caption rides as a second activity item; each share target
                // decides whether to use it. Photos/Messages combine file + text
                // cleanly; others may ignore the string.
                val activityItems = if (!text.isNullOrBlank()) {
                    listOf(fileUrl, text)
                } else {
                    listOf(fileUrl)
                }
                val activityVC = UIActivityViewController(
                    activityItems = activityItems, applicationActivities = null
                )
                activityVC.completionWithItemsHandler =
                    { _, _, _, error ->
                        if (error != null) {
                            onError(Exception(error.localizedDescription))
                        }
                    }
                val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
                if (rootVC != null) {
                    rootVC.presentViewController(activityVC, animated = true, completion = null)
                } else {
                    onError(Exception("Unable to present share sheet"))
                }
            } catch (e: Exception) {
                onError(e)
            }
        }

        override fun saveFile(
            file: Path,
            suggestedName: String,
            onSuccess: (String) -> Unit,
            onError: (Throwable) -> Unit,
        ) = IosAlbumSaver.save(file, suggestedName, onSuccess, onError)

        override fun shareText(text: String, onError: (Throwable) -> Unit) {
            try {
                val activityVC = UIActivityViewController(
                    activityItems = listOf(text), applicationActivities = null
                )
                val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
                rootVC?.presentViewController(activityVC, animated = true, completion = null)
            } catch (e: Exception) {
                onError(e)
            }
        }

        override fun openAppStore(onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }
    }
}
