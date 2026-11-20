@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.diagnostics

import id.homebase.api.diagnostics.CheckReport
import id.homebase.api.diagnostics.checkRun
import id.homebase.api.diagnostics.launchChecks
import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.file.withResolvedFile
import id.homebase.api.foundation.toByteArray
import id.homebase.core.image.CG_BGRA_PREMUL_BITMAP_INFO
import id.homebase.photobase.backup.BackupMediaType
import id.homebase.photobase.backup.IosDeviceChangeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
import platform.Foundation.isEqualToData
import platform.Foundation.writeToURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAdjustmentData
import platform.Photos.PHAsset
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHContentEditingInput
import platform.Photos.PHContentEditingOutput
import platform.Photos.PHPhotoLibrary
import platform.Photos.requestContentEditingInputWithOptions
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Checks the Photos-library paths against the real library: what a send resolves a picked photo to, and what
 * Photos backup exports. Needs full Photos access, and adds its own photos, which it leaves (deleting asks the
 * user).
 */
object PhotoLibraryChecks {
    /** [run] for Swift, called back on a background thread. */
    fun runChecks(onDone: (CheckReport) -> Unit) {
        launchChecks({ run() }, onDone)
    }

    suspend fun run(): CheckReport = checkRun {
        var access = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
        // A grant made outside the app can read as not determined until asked; with no grant, asking shows a prompt.
        if (access == PHAuthorizationStatusNotDetermined) {
            access = withTimeoutOrNull(ACCESS_PROMPT_MS) {
                suspendCancellableCoroutine { continuation ->
                    PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { continuation.resume(it) }
                }
            } ?: access
        }
        if (access != PHAuthorizationStatusAuthorized) {
            fail("no full Photos access (authorization status $access)")
            return@checkRun
        }
        val fileOps = IOSFileOperationsProvider()
        val backup = IosDeviceChangeSource(fileOps)
        val original = jpeg(ORIGINAL_W, ORIGINAL_H)
        val plainId = addPhoto(original)
        val editedId = addPhoto(original).also { edit(it, jpeg(EDITED_W, EDITED_H)) }

        var sent: ByteArray? = null
        verify("a picked photo resolves to its original, and the copy is deleted") {
            val copy = fileOps.withResolvedFile("ph://$plainId") { path ->
                sent = requireOriginal(path, original, plainId, "the copy")
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
        verify("Photos backup exports the original, the same bytes a send gets") {
            val part = backup.export(plainId, BackupMediaType.Image).parts.single()
            try {
                val exported = requireOriginal(part.path, original, plainId, "the export")
                require(part.contentType == "image/jpeg") { "exported as ${part.contentType}" }
                sent?.let { require(it.contentEquals(exported)) { "the export (${exported.size} B) and a send's copy (${it.size} B) differ" } }
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
    }

    // A phone's Photos rewrites a photo's EXIF as it saves it, so the stored original matches what was added only
    // EXIF aside; anything else differing is a re-encode or another resource.
    private fun requireOriginal(path: String, original: NSData, assetId: String, what: String): ByteArray {
        val bytes = checkNotNull(NSData.dataWithContentsOfFile(path)?.toByteArray()) { "$what is unreadable" }
        val expected = original.toByteArray()
        if (withoutExif(bytes).contentEquals(withoutExif(expected))) return bytes
        throw IllegalArgumentException(
            "$what isn't the original: ${bytes.size} B against the original's ${expected.size} B; " +
                "segments ${jpegSegments(bytes)} against ${jpegSegments(expected)}; the asset's resources: ${resources(assetId)}",
        )
    }

    private fun withoutExif(b: ByteArray): ByteArray {
        var at = 2
        while (at + 4 <= b.size && b[at] == 0xFF.toByte()) {
            val marker = b[at + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val end = at + 2 + (((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF))
            if (marker == 0xE1 && b.copyOfRange(at + 4, minOf(at + 8, b.size)).decodeToString() == "Exif") {
                return b.copyOfRange(0, at) + b.copyOfRange(minOf(end, b.size), b.size)
            }
            at = end
        }
        return b
    }

    // A JPEG's segments up to its scan, as marker and length, e.g. "E1 Exif 1147": where two files part.
    private fun jpegSegments(b: ByteArray): String = buildList {
        var at = 2
        while (at + 4 <= b.size && b[at] == 0xFF.toByte() && size < 12) {
            val marker = b[at + 1].toInt() and 0xFF
            val length = ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
            val name = b.copyOfRange(at + 4, minOf(at + 8, b.size)).decodeToString().filter { it.isLetterOrDigit() }
            add("${marker.toString(16).uppercase()} $name $length")
            if (marker == 0xDA) break
            at += 2 + length
        }
    }.joinToString(prefix = "[", postfix = "]")

    private fun resources(assetId: String): String {
        val asset = asset(assetId) ?: return "the asset is gone"
        return PHAssetResource.assetResourcesForAsset(asset).filterIsInstance<PHAssetResource>()
            .joinToString(prefix = "[", postfix = "]") { "type ${it.type} ${it.uniformTypeIdentifier} ${it.originalFilename}" }
    }

    private fun asset(id: String) = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null).firstObject as? PHAsset

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
        val asset = checkNotNull(asset(id)) { "photo $id not found" }
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
    private const val ACCESS_PROMPT_MS = 20_000L
}
