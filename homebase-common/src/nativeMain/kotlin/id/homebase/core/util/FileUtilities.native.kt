package id.homebase.core.util

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
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
        override fun shareFile(file: Path, onError: (Throwable) -> Unit) {
            try {
                val fileUrl = NSURL.fileURLWithPath(file.toString())
                val activityVC = UIActivityViewController(
                    activityItems = listOf(fileUrl), applicationActivities = null
                )
                val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
                rootVC?.presentViewController(activityVC, animated = true, completion = null)
            } catch (e: Exception) {
                onError(e)
            }
        }

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
