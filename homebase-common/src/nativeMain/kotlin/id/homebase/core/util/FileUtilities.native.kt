package id.homebase.core.util

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
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

        @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
        override fun saveFile(
            file: Path,
            suggestedName: String,
            onSuccess: (String) -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            val fileUrl = NSURL.fileURLWithPath(file.toString())
            val extension = suggestedName.substringAfterLast('.', "").lowercase()
            val isImage = extension in setOf("jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tiff")
            val isVideo = extension in setOf("mp4", "mov", "m4v", "avi", "mkv", "webm", "3gp")

            if (isImage || isVideo) {
                // Save to Photos library (add-only access)
                val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
                if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
                    PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { newStatus ->
                        if (newStatus == PHAuthorizationStatusAuthorized || newStatus == PHAuthorizationStatusLimited) {
                            saveToPhotoLibrary(fileUrl, isVideo, onSuccess, onError)
                        } else {
                            onError(Exception("Photo library access denied. Please enable in Settings."))
                        }
                    }
                } else {
                    saveToPhotoLibrary(fileUrl, isVideo, onSuccess, onError)
                }
            } else {
                // Save other files to Documents directory
                val scoped = fileUrl.startAccessingSecurityScopedResource()
                try {
                    val safeName = suggestedName
                        .replace('/', '_')
                        .replace('\\', '_')
                        .replace('\u0000', '_')
                    val fileManager = NSFileManager.defaultManager
                    val paths = NSSearchPathForDirectoriesInDomains(
                        NSDocumentDirectory,
                        NSUserDomainMask,
                        true,
                    )
                    val documentsDir = paths.firstOrNull() as? String
                        ?: throw Exception("Could not find Documents directory")
                    val destPath = "$documentsDir/$safeName"
                    val sourcePath = fileUrl.path
                        ?: throw Exception("Source URL has no filesystem path: $fileUrl")

                    memScoped {
                        // Remove existing file if present
                        if (fileManager.fileExistsAtPath(destPath)) {
                            val removeErr = alloc<ObjCObjectVar<NSError?>>()
                            if (!fileManager.removeItemAtPath(destPath, removeErr.ptr)) {
                                val msg = removeErr.value?.localizedDescription ?: "unknown error"
                                throw Exception("Failed to remove existing file at $destPath: $msg")
                            }
                        }
                        val copyErr = alloc<ObjCObjectVar<NSError?>>()
                        if (!fileManager.copyItemAtPath(sourcePath, destPath, copyErr.ptr)) {
                            val msg = copyErr.value?.localizedDescription ?: "unknown error"
                            throw Exception("Failed to copy file to $destPath: $msg")
                        }
                    }
                    onSuccess("Documents")
                } catch (e: Exception) {
                    onError(e)
                } finally {
                    if (scoped) fileUrl.stopAccessingSecurityScopedResource()
                }
            }
        }

        private fun saveToPhotoLibrary(
            fileUrl: NSURL,
            isVideo: Boolean,
            onSuccess: (String) -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            val scoped = fileUrl.startAccessingSecurityScopedResource()
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                changeBlock = {
                    if (isVideo) {
                        PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(fileUrl)
                    } else {
                        PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(fileUrl)
                    }
                },
                completionHandler = { success, error ->
                    if (scoped) fileUrl.stopAccessingSecurityScopedResource()
                    if (success) {
                        onSuccess("Photos")
                    } else {
                        onError(Exception(error?.localizedDescription ?: "Failed to save to Photos"))
                    }
                },
            )
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
