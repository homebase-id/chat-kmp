@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.diagnostics

import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.file.withResolvedFile
import id.homebase.core.image.CG_BGRA_PREMUL_BITMAP_INFO
import id.homebase.photobase.backup.BackupMediaType
import id.homebase.photobase.backup.IosDeviceChangeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAdjustmentData
import platform.Photos.PHAsset
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHContentEditingInput
import platform.Photos.PHContentEditingOutput
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Checks the Photos-library paths against the real library: what a send resolves a picked photo to, and what
 * Photos backup exports. Needs full Photos access, and adds its own photos, which it leaves (deleting asks the
 * user). Returns one line per failed check; empty means every check passed.
 */
object PhotoLibraryChecks {
    suspend fun run(): List<String> = withContext(Dispatchers.Default) {
        if (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite) != PHAuthorizationStatusAuthorized) {
            return@withContext listOf("no full Photos access")
        }
        val failures = mutableListOf<String>()
        suspend fun verify(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += "$name: $e"
            }
        }
        val fileOps = IOSFileOperationsProvider()
        val backup = IosDeviceChangeSource(fileOps)
        val original = jpeg(ORIGINAL_W, ORIGINAL_H)
        val plainId = addPhoto(original)
        val editedId = addPhoto(original).also { edit(it, jpeg(EDITED_W, EDITED_H)) }

        verify("a picked photo resolves to its original bytes, and the copy is deleted") {
            val copy = fileOps.withResolvedFile("ph://$plainId") { path ->
                require(NSData.dataWithContentsOfFile(path)?.isEqualToData(original) == true) { "the copy at $path isn't the original's bytes" }
                path
            }
            require(!NSFileManager.defaultManager.fileExistsAtPath(copy)) { "the copy at $copy is still there" }
        }
        verify("an edited photo resolves to its edit") {
            fileOps.withResolvedFile("ph://$editedId") { path ->
                val size = pixelSize(path)
                require(size == EDITED_W to EDITED_H) { "resolved to ${size.first}x${size.second}, the edit is ${EDITED_W}x$EDITED_H" }
            }
        }
        verify("a photo that's gone throws SourceUnavailableException") {
            val outcome = runCatching { fileOps.resolveToFilePath(MISSING) }
            require(outcome.exceptionOrNull() is SourceUnavailableException) { "got ${outcome.getOrNull() ?: outcome.exceptionOrNull()}" }
        }
        verify("Photos backup exports the original's bytes") {
            val part = backup.export(plainId, BackupMediaType.Image).parts.single()
            try {
                require(NSData.dataWithContentsOfFile(part.path)?.isEqualToData(original) == true) { "the export isn't the original's bytes" }
                require(part.contentType == "image/jpeg") { "exported as ${part.contentType}" }
            } finally {
                fileOps.deleteTempFile(part.path)
            }
        }
        verify("Photos backup exports an edited photo's edit") {
            val part = backup.export(editedId, BackupMediaType.Image).parts.single()
            try {
                val size = pixelSize(part.path)
                require(size == EDITED_W to EDITED_H) { "exported ${size.first}x${size.second}, the edit is ${EDITED_W}x$EDITED_H" }
            } finally {
                fileOps.deleteTempFile(part.path)
            }
        }
        failures
    }

    private suspend fun addPhoto(data: NSData): String {
        var id: String? = null
        performChanges {
            val request = PHAssetCreationRequest.creationRequestForAsset()
            request.addResourceWithType(PHAssetResourceTypePhoto, data = data, options = null)
            id = request.placeholderForCreatedAsset?.localIdentifier
        }
        return checkNotNull(id) { "Photos gave the new photo no identifier" }
    }

    private suspend fun edit(id: String, rendered: NSData) {
        val asset = checkNotNull(PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null).firstObject as? PHAsset) { "photo $id not found" }
        val input = suspendCancellableCoroutine<PHContentEditingInput> { continuation ->
            asset.requestContentEditingInputWithOptions(null) { input, _ ->
                if (input != null) continuation.resume(input) else continuation.resumeWithException(IllegalStateException("no editing input for $id"))
            }
        }
        val output = PHContentEditingOutput(contentEditingInput = input)
        output.adjustmentData = PHAdjustmentData(formatIdentifier = "id.homebase.checks", formatVersion = "1", data = rendered)
        check(rendered.writeToURL(output.renderedContentURL, atomically = true)) { "couldn't write the edit" }
        performChanges { PHAssetChangeRequest.changeRequestForAsset(asset).contentEditingOutput = output }
    }

    private suspend fun performChanges(changes: () -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(changes) { success, error ->
            if (success) continuation.resume(Unit)
            else continuation.resumeWithException(IllegalStateException("Photos refused the change: ${error?.localizedDescription}"))
        }
    }

    private fun jpeg(width: Int, height: Int): NSData {
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(null, width.toULong(), height.toULong(), 8u, 0u, space, CG_BGRA_PREMUL_BITMAP_INFO)
        CGColorSpaceRelease(space)
        checkNotNull(context) { "no ${width}x$height bitmap context" }
        CGContextSetRGBFillColor(context, 0.2, 0.5, 0.8, 1.0)
        CGContextFillRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        val image = checkNotNull(CGBitmapContextCreateImage(context)) { "no ${width}x$height image" }
        CGContextRelease(context)
        try {
            return checkNotNull(UIImageJPEGRepresentation(UIImage.imageWithCGImage(image), 0.9)) { "no JPEG" }
        } finally {
            CGImageRelease(image)
        }
    }

    private fun pixelSize(path: String): Pair<Int, Int> {
        val image = checkNotNull(UIImage.imageWithContentsOfFile(path)) { "$path isn't an image" }
        return image.size.useContents { (width * image.scale).toInt() to (height * image.scale).toInt() }
    }

    private const val ORIGINAL_W = 400
    private const val ORIGINAL_H = 300
    private const val EDITED_W = 200
    private const val EDITED_H = 100
    private const val MISSING = "ph://00000000-0000-0000-0000-000000000000/L0/001"
}
