package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.Buffer
import okio.FileSystem
import okio.ForwardingSource
import okio.Source
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class IOSFileOperationsProvider : OkioFileOperationsProvider(FileSystem.SYSTEM, "") {

    private fun isPhotoLibraryPath(path: String) = path.startsWith("ph://") || path.contains("/L0/")

    // Held from open to close so Files-app picks outside the sandbox stay readable for the whole stream.
    @OptIn(ExperimentalForeignApi::class)
    override fun openSource(path: String): Source {
        val url = NSURL.fileURLWithPath(path)
        val accessed = url.startAccessingSecurityScopedResource()
        val source = try {
            super.openSource(path)
        } catch (t: Throwable) {
            if (accessed) url.stopAccessingSecurityScopedResource()
            throw t
        }
        return object : ForwardingSource(source) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    if (accessed) url.stopAccessingSecurityScopedResource()
                }
            }
        }
    }

    // PHImageManager has no byte-stream API; photo-sized assets are read whole. Videos never come
    // through here — they are materialized to a real file via resolveToFilePath.
    override fun openFileInput(path: String): InputProvider {
        if (isPhotoLibraryPath(path)) {
            return InputProvider {
                val bytes = runBlocking { readPhotoLibraryAsset(path) }
                Buffer().apply { write(bytes) }
            }
        }
        return super.openFileInput(path)
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        if (isPhotoLibraryPath(path)) readPhotoLibraryAsset(path) else super.readFileBytes(path)

    override suspend fun readFileHeaderBytes(path: String, maxBytes: Int): ByteArray =
        if (isPhotoLibraryPath(path)) readPhotoLibraryAsset(path).let { if (it.size <= maxBytes) it else it.copyOf(maxBytes) }
        else super.readFileHeaderBytes(path, maxBytes)

    override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> =
        if (isPhotoLibraryPath(path)) flow { emit(readPhotoLibraryAsset(path)) }
        else super.readFileAsFlow(path, chunkSize)

    // getFileSize can't size a Photos-library asset (it reports 0), so probe the library
    // directly: the fetch returns nothing once the asset is deleted or the grant is revoked.
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun sourceExists(path: String): Boolean {
        if (isPhotoLibraryPath(path)) {
            val assetId = if (path.startsWith("ph://")) path.removePrefix("ph://") else path
            val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(assetId), options = null)
            return fetchResult.count.toInt() > 0
        }
        return super.sourceExists(path)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun getCacheDirectory(): String {
        val fileManager = NSFileManager.defaultManager
        val cacheUrl =
            fileManager.URLForDirectory(
                directory = NSCachesDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null
            )
        return cacheUrl?.path ?: NSTemporaryDirectory()
    }

    // Encrypted, ready-to-transmit payloads live in the durable staging dir (#842) — under
    // Application Support, NOT Caches: iOS purges Caches under storage pressure, which deleted
    // staged payloads out from under long-lived outbox rows (the ENOENT retry loop). The dir is
    // excluded from iCloud backup on every creation (the attribute doesn't survive recreation):
    // staged payloads are regeneratable and their outbox DB rows don't ride a restore.
    // The interface default routes writeBytesToOutboxTempFile through this dir.
    @OptIn(ExperimentalForeignApi::class)
    override fun getOutboxStagingDirectory(): String {
        val fm = NSFileManager.defaultManager
        val supportUrl = fm.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null
        )
        val base = supportUrl?.path ?: return super.getOutboxStagingDirectory()
        val dir = "${base.trimEnd('/')}/$OUTBOX_STAGING_DIR_NAME"
        if (!fm.fileExistsAtPath(dir)) {
            fm.createDirectoryAtPath(dir, true, null, null)
        }
        NSURL.fileURLWithPath(dir, isDirectory = true).setResourceValue(
            value = NSNumber(bool = true),
            forKey = NSURLIsExcludedFromBackupKey,
            error = null
        )
        return dir
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun resolveToFilePath(path: String): String {
        if (path.startsWith("ph://") || path.contains("/L0/")) {
            // A video PHAsset must be read via PHAssetResourceManager: requestImageDataForAsset
            // (used by readPhotoLibraryAsset) is image-only and returns a tiny poster frame for
            // videos — that's the "video sends as a few-KB file" bug. Stream the original movie
            // resource straight to the temp file (also avoids holding the whole video in memory).
            val videoAsset = phVideoAssetOrNull(path)
            if (videoAsset != null) {
                val tmpPath = "${NSTemporaryDirectory()}resolved_${NSUUID().UUIDString}.mov"
                writeVideoAssetToFile(videoAsset, tmpPath)
                return tmpPath
            }

            val bytes = readPhotoLibraryAsset(path)
            val tmpPath = "${NSTemporaryDirectory()}resolved_${NSUUID().UUIDString}.mov"
            val data = bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            data.writeToFile(tmpPath, atomically = true)
            return tmpPath
        }

        return path
    }

    /**
     * The [PHAsset] for a Photos-library video path iff it is a video, else null (images, or a
     * non-library path). Accepts BOTH a `ph://<id>` URI and the raw PHAsset localIdentifier
     * (`<uuid>/L0/nnn`): the gallery picker hands the send path the raw localIdentifier — never a
     * `ph://` URI — so gating on `ph://` alone left gallery video picks falling through to the
     * image-only `requestImageDataForAsset` path, which returns a few-KB poster frame for `.mp4`
     * (the "mp4 sends as a tiny file" bug). Mirrors the `ph:// || /L0/` guard used above.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun phVideoAssetOrNull(path: String): PHAsset? {
        val assetId = when {
            path.startsWith("ph://") -> path.removePrefix("ph://")
            path.contains("/L0/") -> path
            else -> return null
        }
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(assetId), options = null)
        if (fetchResult.count.toInt() == 0) return null
        val asset = fetchResult.objectAtIndex(0u) as PHAsset
        return if (asset.mediaType == PHAssetMediaTypeVideo) asset else null
    }

    /** Streams a video PHAsset's original movie resource to [destPath] via PHAssetResourceManager. */
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun writeVideoAssetToFile(asset: PHAsset, destPath: String): Unit =
        suspendCancellableCoroutine { continuation ->
            val videoResource = PHAssetResource.assetResourcesForAsset(asset)
                .filterIsInstance<PHAssetResource>()
                .firstOrNull { it.type == PHAssetResourceTypeVideo || it.type == PHAssetResourceTypeFullSizeVideo }
            if (videoResource == null) {
                continuation.resumeWithException(
                    IllegalStateException("No video resource for asset ${asset.localIdentifier}")
                )
                return@suspendCancellableCoroutine
            }

            val options = PHAssetResourceRequestOptions().apply { setNetworkAccessAllowed(true) }
            val destUrl = NSURL.fileURLWithPath(destPath)
            PHAssetResourceManager.defaultManager().writeDataForAssetResource(
                videoResource,
                toFile = destUrl,
                options = options,
                completionHandler = { error ->
                    if (error != null) {
                        continuation.resumeWithException(
                            IllegalStateException("Failed to export video resource: ${error.localizedDescription}")
                        )
                    } else {
                        continuation.resume(Unit)
                    }
                },
            )
        }

    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    private suspend fun readPhotoLibraryAsset(phUri: String): ByteArray = suspendCancellableCoroutine { continuation ->
            val assetId = phUri.removePrefix("ph://")
            val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(assetId), options = null)

            if (fetchResult.count.toInt() == 0) {
                continuation.resumeWithException(IllegalArgumentException("Asset not found: $phUri"))
                return@suspendCancellableCoroutine
            }

            val asset = fetchResult.objectAtIndex(0u) as PHAsset
            val options = PHImageRequestOptions().apply {
                setSynchronous(false)
                setDeliveryMode(PHImageRequestOptionsDeliveryModeHighQualityFormat)
            }

            platform.Photos.PHImageManager.defaultManager().requestImageDataForAsset(
                asset = asset,
                options = options,
                resultHandler = { data, _, _, _ ->
                    if (data != null) {
                        val bytes = ByteArray(data.length.toInt())
                        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
                        continuation.resume(bytes)
                    } else {
                        continuation.resumeWithException(IllegalStateException("Failed to load asset data"))
                    }
                }
            )
        }


}
