package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import okio.FileSystem
import okio.ForwardingSource
import okio.Source
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceTypeFullSizePhoto
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun isPhotoLibraryPath(path: String) = path.startsWith("ph://") || path.contains("/L0/")

/** Opens [path]'s security scope, so a Files-app pick outside the sandbox stays readable; call the result to close it. */
@OptIn(ExperimentalForeignApi::class)
internal fun accessSecurityScope(path: String): () -> Unit {
    val url = NSURL.fileURLWithPath(path)
    val accessed = url.startAccessingSecurityScopedResource()
    return { if (accessed) url.stopAccessingSecurityScopedResource() }
}

class IOSFileOperationsProvider : OkioFileOperationsProvider(FileSystem.SYSTEM, "") {

    override suspend fun readFileRange(path: String, offset: Long, length: Int): ByteArray {
        val closeScope = accessSecurityScope(path)
        try {
            return super.readFileRange(path, offset, length)
        } finally {
            closeScope()
        }
    }

    override suspend fun openRanges(path: String): FileRanges {
        val closeScope = accessSecurityScope(path)
        val ranges = try {
            super.openRanges(path)
        } catch (t: Throwable) {
            closeScope()
            throw t
        }
        return object : FileRanges by ranges {
            override fun close() {
                try {
                    ranges.close()
                } finally {
                    closeScope()
                }
            }
        }
    }

    override fun openSource(path: String): Source {
        val closeScope = accessSecurityScope(path)
        val source = try {
            super.openSource(path)
        } catch (t: Throwable) {
            closeScope()
            throw t
        }
        return object : ForwardingSource(source) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    closeScope()
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

    override fun isPhotoLibraryAsset(path: String): Boolean = isPhotoLibraryPath(path)

    override suspend fun resolveToFilePath(path: String): String {
        if (!isPhotoLibraryPath(path)) return path
        return withContext(Dispatchers.Default) {
            val asset = phAssetOrNull(path) ?: throw SourceUnavailableException(path)
            // requestImageDataForAsset is image-only (a video gets its poster frame), so a video streams its own resource.
            if (asset.mediaType == PHAssetMediaTypeVideo) {
                val tmpPath = "${NSTemporaryDirectory()}resolved_${NSUUID().UUIDString}.mov"
                writeVideoAssetToFile(asset, tmpPath)
                return@withContext tmpPath
            }
            val resources = PHAssetResource.assetResourcesForAsset(asset).filterIsInstance<PHAssetResource>()
            val photo = PhotoLibraryResources.pick(resources, PHAssetResourceTypeFullSizePhoto, PHAssetResourceTypePhoto)
            val extension = photo?.let { extensionForContentType(PhotoLibraryResources.contentTypeOf(it)) } ?: "img"
            val tmpPath = createUploadTempPath("resolved_", ".$extension")
            try {
                if (photo != null) {
                    PhotoLibraryResources.write(photo, tmpPath, mayDownload = true) { IllegalStateException("export refused") }
                } else {
                    // An image asset with no photo resource: what PhotoKit renders for it, as the send always had.
                    writeStream(tmpPath, flowOf(readPhotoLibraryAsset(path)))
                }
            } catch (e: Throwable) {
                runCatching { deleteTempFile(tmpPath) }
                throw e
            }
            tmpPath
        }
    }

    /** The [PHAsset] for a Photos-library path (`ph://<id>` or a raw `<uuid>/L0/nnn` identifier), else null. */
    @OptIn(ExperimentalForeignApi::class)
    private fun phAssetOrNull(path: String): PHAsset? {
        val assetId = when {
            path.startsWith("ph://") -> path.removePrefix("ph://")
            path.contains("/L0/") -> path
            else -> return null
        }
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(assetId), options = null)
        if (fetchResult.count.toInt() == 0) return null
        return fetchResult.objectAtIndex(0u) as PHAsset
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

    @OptIn(ExperimentalForeignApi::class)
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
