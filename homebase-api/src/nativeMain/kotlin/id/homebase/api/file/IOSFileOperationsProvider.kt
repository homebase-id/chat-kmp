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
import kotlinx.io.RawSource
import kotlinx.io.buffered
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData
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

class IOSFileOperationsProvider : FileOperationsProvider {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun readFileData(path: String): ByteArray {
        val url = NSURL.fileURLWithPath(path)
        val accessed = url.startAccessingSecurityScopedResource()
        try {
            val data = NSData.dataWithContentsOfFile(path)
                ?: error("Unable to read file at $path")
            val bytes = ByteArray(data.length.toInt())
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
            return bytes
        } finally {
            if (accessed) url.stopAccessingSecurityScopedResource()
        }
    }

    /**
     * Lazy, CHUNKED multipart upload source (#947). The previous implementation
     * buffered the entire staged encrypted payload (`Buffer().write(readFileData(path))`)
     * when the outbox drained — with single-file HLS that meant a whole video in RAM
     * at send time, despite streamed encryption (#842). Now the ktor block opens a
     * fresh [FileHandleRawSource] per invocation (the block is re-invoked on ktor
     * retries and outbox re-drives, so it must be re-creatable) and streams 64 KB at
     * a time. `size` feeds the multipart Content-Length; app-level progress totals
     * come from `calculateUploadSize`, not from here.
     *
     * Photos-library sources (`ph://` / raw PHAsset ids) keep the whole-file path:
     * PHImageManager has no byte-stream API, these are photo-sized, and videos never
     * come through here — they're materialized to a real file via [resolveToFilePath].
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun openFileInput(path: String): InputProvider {
        if (path.startsWith("ph://") || path.contains("/L0/")) {
            return InputProvider {
                val bytes = runBlocking { readPhotoLibraryAsset(path) }
                Buffer().apply { write(bytes) }
            }
        }
        return InputProvider(size = getFileSize(path).takeIf { it > 0 }) {
            FileHandleRawSource(path).buffered()
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun readFileBytes(path: String): ByteArray {
        if (path.startsWith("ph://") || path.contains("/L0/")) {
            return readPhotoLibraryAsset(path)
        }
        return readFileData(path)
    }

    /**
     * Real chunked streaming via [NSFileHandle] (#842) — without this override the
     * interface default loads the ENTIRE file as one chunk, so "streamed" encryption
     * of a large payload still spiked memory by the full file size on iOS.
     *
     * Photos-library sources (`ph://` / raw PHAsset ids) keep the single-chunk path:
     * PHImageManager has no byte-stream API for image data, these are photo-sized
     * (videos never come through here — they're materialized to a real file via
     * [resolveToFilePath] first), and the bytes must come from the library, not disk.
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> = flow {
        if (path.startsWith("ph://") || path.contains("/L0/")) {
            emit(readPhotoLibraryAsset(path))
            return@flow
        }
        val url = NSURL.fileURLWithPath(path)
        val accessed = url.startAccessingSecurityScopedResource()
        try {
            val handle = NSFileHandle.fileHandleForReadingAtPath(path)
                ?: error("Unable to read file at $path")
            try {
                while (true) {
                    val data = handle.readDataOfLength(chunkSize.toULong())
                    val len = data.length.toInt()
                    if (len == 0) break
                    val bytes = ByteArray(len)
                    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
                    emit(bytes)
                }
            } finally {
                handle.closeFile()
            }
        } finally {
            if (accessed) url.stopAccessingSecurityScopedResource()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun deleteTempFile(path: String): Boolean {
        return runCatching {
            val fileManager = NSFileManager.defaultManager

            if (!fileManager.fileExistsAtPath(path)) {
                true
            } else {
                fileManager.removeItemAtPath(path, error = null)
            }
        }.getOrDefault(false)
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

    @OptIn(ExperimentalForeignApi::class)
    override fun getFileSize(path: String): Long {
        val attrs =
            NSFileManager.defaultManager.attributesOfItemAtPath(
                path = path,
                error = null
            )

        val size = attrs?.get("NSFileSize") as? NSNumber
        return size?.longLongValue ?: 0L
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun sourceExists(path: String): Boolean {
        if (path.startsWith("ph://") || path.contains("/L0/")) {
            // getFileSize can't size a Photos-library asset (it reports 0), so probe the
            // library directly: the fetch returns nothing once the asset is deleted or the
            // photo-library grant is revoked.
            val assetId = if (path.startsWith("ph://")) path.removePrefix("ph://") else path
            val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(assetId), options = null)
            return fetchResult.count.toInt() > 0
        }
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    override suspend fun writeBytesToTempFile(
        bytes: ByteArray,
        prefix: String,
        suffix: String
    ): String = writeBytesIn(CacheAudit.UPLOAD_TEMP_DIR_NAME, bytes, prefix, suffix)

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

    // upload-temp lives under the Caches dir (not NSTemporaryDirectory()), so the Storage
    // screen counts it and the CacheSweeper reaps it on every startup (disposable; #844 PR4).
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun writeBytesIn(dirName: String, bytes: ByteArray, prefix: String, suffix: String): String {
        val cacheDir = getCacheDirectory().trimEnd('/')
        val tempDir = "$cacheDir/$dirName"
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(tempDir)) {
            fm.createDirectoryAtPath(tempDir, true, null, null)
        }
        val filePath = "$tempDir/$prefix${NSUUID().UUIDString}$suffix"

        val data =
            bytes.usePinned { pinned ->
                NSData.create(
                    bytes = pinned.addressOf(0),
                    length = bytes.size.toULong()
                )
            }

        data.writeToFile(filePath, atomically = true)
        return filePath
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun writeBytesToShareOutboundFile(
        bytes: ByteArray,
        suffix: String,
    ): String {
        val cacheDir = getCacheDirectory().trimEnd('/')
        val dir = "$cacheDir/$SHARE_OUTBOUND_DIR_NAME"
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(dir)) {
            fm.createDirectoryAtPath(dir, true, null, null)
        }
        val filePath = "$dir/share_${NSUUID().UUIDString}$suffix"
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        if (!data.writeToFile(filePath, atomically = true)) {
            error("Failed to write share file to $filePath")
        }
        return filePath
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun writeStream(
        path: String,
        data: Flow<ByteArray>
    ) {

        val fileManager = NSFileManager.defaultManager

        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createFileAtPath(path, contents = null, attributes = null)
        }

        val handle = NSFileHandle.fileHandleForWritingAtPath(path)
            ?: error("Unable to open file for writing: $path")

        handle.seekToEndOfFile()

        data.collect { chunk ->

            val nsData =
                chunk.usePinned { pinned ->
                    NSData.create(
                        bytes = pinned.addressOf(0),
                        length = chunk.size.toULong()
                    )
                }

            handle.writeData(nsData)
        }

        handle.closeFile()
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

/**
 * A [RawSource] over an [NSFileHandle] read in [chunkSize] steps — the lazy backing
 * for [IOSFileOperationsProvider.openFileInput] (#947). Holds at most one chunk in
 * memory at a time. Mirrors the chunk loop of `readFileAsFlow` (incl. the pinned
 * `memcpy` idiom) and the security-scoped-resource guard of `readFileData`: access
 * is acquired on construction and released in [close] alongside the file handle.
 * A missing file throws the same `IllegalStateException("Unable to read file …")`
 * shape as the other readers.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class FileHandleRawSource(
    path: String,
    private val chunkSize: Int = FileOperationsProvider.DEFAULT_HEADER_BYTES,
) : RawSource {
    private val url = NSURL.fileURLWithPath(path)
    private val accessed = url.startAccessingSecurityScopedResource()
    private val handle = NSFileHandle.fileHandleForReadingAtPath(path)
        ?: run {
            if (accessed) url.stopAccessingSecurityScopedResource()
            error("Unable to read file at $path")
        }
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount must be non-negative: $byteCount" }
        if (byteCount == 0L) return 0L
        check(!closed) { "source is closed" }
        val data = handle.readDataOfLength(minOf(byteCount, chunkSize.toLong()).toULong())
        val len = data.length.toInt()
        if (len == 0) return -1L
        val bytes = ByteArray(len)
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        sink.write(bytes)
        return len.toLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        handle.closeFile()
        if (accessed) url.stopAccessingSecurityScopedResource()
    }
}
