package id.homebase.core.util

import co.touchlab.kermit.Logger
import id.homebase.api.lib.image.ImageFormatDetector
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

private const val TAG = "IosAlbumSaver"

object IosAlbumSaver : AlbumSaver {

    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    override fun save(
        file: Path,
        suggestedName: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val fileUrl = NSURL.fileURLWithPath(file.toString())
        val extension = suggestedName.substringAfterLast('.', "").lowercase()
        val isImage =
            extension in setOf("jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tiff")
        val isVideo = extension in setOf("mp4", "mov", "m4v", "avi", "mkv", "webm", "3gp")

        if (isImage || isVideo) {
            // Save to Photos library (add-only access)
            when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)) {
                PHAuthorizationStatusAuthorized, PHAuthorizationStatusLimited ->
                    saveToPhotoLibrary(fileUrl, isVideo, onSuccess, onError)

                // Only NotDetermined may prompt. A prior denial must fail fast instead, or
                // background auto-save re-asks on every incoming photo.
                PHAuthorizationStatusNotDetermined ->
                    PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { newStatus ->
                        if (newStatus == PHAuthorizationStatusAuthorized ||
                            newStatus == PHAuthorizationStatusLimited
                        ) {
                            saveToPhotoLibrary(fileUrl, isVideo, onSuccess, onError)
                        } else {
                            onError(
                                AlbumAccessDeniedException(
                                    "Photo library access denied. Please enable in Settings."
                                )
                            )
                        }
                    }

                else -> onError(
                    AlbumAccessDeniedException(
                        "Photo library access denied. Please enable in Settings."
                    )
                )
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
                // "Files" (not "Documents") — the saved file shows up in the
                // Files app under On My iPhone > Homebase, which is where this
                // points the user. Requires the Info.plist file-sharing keys.
                onSuccess("Files")
            } catch (e: Exception) {
                onError(e)
            } finally {
                if (scoped) fileUrl.stopAccessingSecurityScopedResource()
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun saveToPhotoLibrary(
        fileUrl: NSURL,
        isVideo: Boolean,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val scoped = fileUrl.startAccessingSecurityScopedResource()

        // Some image formats this platform serves (notably WebP, also AVIF)
        // can be *displayed* by iOS but are rejected by the Photos library on
        // import with PHPhotosError 3302 (invalidResource) — the cause of the
        // "couldn't save photo" failures. UIImage can still decode them, so
        // re-encode such files to JPEG and add them as raw photo-resource
        // data. JPEG/PNG/GIF/HEIC import losslessly via the original file URL.
        val reencodedJpeg: NSData? = if (isVideo) null else jpegForUnsupportedPhotoFormat(fileUrl)

        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            changeBlock = {
                when {
                    isVideo ->
                        PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(fileUrl)

                    reencodedJpeg != null ->
                        PHAssetCreationRequest.creationRequestForAsset()
                            .addResourceWithType(
                                type = PHAssetResourceTypePhoto,
                                data = reencodedJpeg,
                                options = null,
                            )

                    else ->
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

    /**
     * If [fileUrl] points to an image in a format the Photos library can't
     * import (WebP/AVIF/BMP/unknown — anything other than JPEG/PNG/GIF/HEIC),
     * decode it with UIImage and return JPEG bytes suitable for
     * [PHAssetCreationRequest.addResourceWithType]. Returns null for formats
     * Photos accepts directly (so the original bytes are preserved) or when
     * the file can't be read/decoded (so Photos still gets to try the
     * original and surface its own error).
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun jpegForUnsupportedPhotoFormat(fileUrl: NSURL): NSData? {
        val data = NSData.dataWithContentsOfURL(fileUrl) ?: return null
        val format = ImageFormatDetector.detectFormat(data.headerBytes(16))
        val importsDirectly = format in setOf(
            "image/jpeg", "image/png", "image/gif", "image/heic", "image/heif",
        )
        if (importsDirectly) return null

        Logger.d(tag = TAG) {
            "saveToPhotoLibrary: re-encoding $format to JPEG (Photos cannot import it directly)"
        }
        val uiImage = UIImage.imageWithData(data)
        if (uiImage == null) {
            Logger.w(tag = TAG) {
                "saveToPhotoLibrary: UIImage could not decode $format; letting Photos try the original"
            }
            return null
        }
        return UIImageJPEGRepresentation(uiImage, 0.95)
    }

    /** Copy up to [count] leading bytes of this [NSData] into a [ByteArray]. */
    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.headerBytes(count: Int): ByteArray {
        val n = minOf(count.toULong(), length).toInt()
        val out = ByteArray(n)
        if (n > 0) {
            out.usePinned { pinned ->
                memcpy(pinned.addressOf(0), bytes, n.toULong())
            }
        }
        return out
    }
}
