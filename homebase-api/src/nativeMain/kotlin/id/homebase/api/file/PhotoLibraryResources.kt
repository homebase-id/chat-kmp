@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.file

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSOutputStream
import platform.Foundation.outputStreamToFileAtPath
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceDataRequestID
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceType
import platform.UniformTypeIdentifiers.UTType
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reading a Photos-library asset's own resources to files: shared by the send path and Photos backup. */
object PhotoLibraryResources {
    /** The [preferred] resource (a full-size edit) when there is one, else the [fallback] (the original). */
    fun pick(resources: List<PHAssetResource>, preferred: PHAssetResourceType, fallback: PHAssetResourceType): PHAssetResource? =
        resources.firstOrNull { it.type == preferred } ?: resources.firstOrNull { it.type == fallback }

    fun contentTypeOf(resource: PHAssetResource): String =
        UTType.typeWithIdentifier(resource.uniformTypeIdentifier)?.preferredMIMEType ?: "application/octet-stream"

    /**
     * Streams [resource] to [path], throwing [refused] when PhotoKit refuses a run that may not download. Not
     * writeDataForAssetResource: only a data request can be cancelled, and a download must stop with its caller.
     */
    suspend fun write(resource: PHAssetResource, path: String, mayDownload: Boolean, refused: () -> Throwable) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val options = PHAssetResourceRequestOptions().apply { networkAccessAllowed = mayDownload }
            val manager = PHAssetResourceManager.defaultManager()
            val stream = NSOutputStream.outputStreamToFileAtPath(path, append = false)
            stream.open()
            var writeFailed = false
            var request: PHAssetResourceDataRequestID = 0
            request = manager.requestDataForAssetResource(
                resource,
                options,
                dataReceivedHandler = { data ->
                    if (data != null && !writeFailed && !stream.writeAll(data)) {
                        writeFailed = true
                        manager.cancelDataRequest(request)
                    }
                },
                completionHandler = { error ->
                    stream.close()
                    when {
                        writeFailed -> continuation.resumeWithException(IllegalStateException("export failed: could not write the file"))
                        error == null -> continuation.resume(Unit)
                        // Any refusal, not one error code: a real failure still counts when a run that may download meets it.
                        !mayDownload -> continuation.resumeWithException(refused())
                        else -> continuation.resumeWithException(IllegalStateException("export failed: ${error.localizedDescription}"))
                    }
                },
            )
            continuation.invokeOnCancellation { manager.cancelDataRequest(request) }
        }

    private fun NSOutputStream.writeAll(data: NSData): Boolean {
        val bytes = data.bytes?.reinterpret<UByteVar>() ?: return data.length == 0uL
        var written = 0L
        while (written < data.length.toLong()) {
            val count = write(bytes + written, data.length - written.toULong())
            if (count <= 0) return false
            written += count
        }
        return true
    }
}
