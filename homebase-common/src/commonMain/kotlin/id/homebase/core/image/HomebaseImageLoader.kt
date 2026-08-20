package id.homebase.core.image

import co.touchlab.kermit.Logger
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.PayloadTooLargeException
import id.homebase.api.client.RetryConfig
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.client.withRetry
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.util.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** Image data container */
// TODO: Rename to memoryImage?
data class CachedImage(val bytes: ByteArray, val contentType: String, val size: ImageSize?) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as CachedImage
        return bytes.contentEquals(other.bytes) && contentType == other.contentType && size == other.size
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (size?.hashCode() ?: 0)
        return result
    }
}

/**
 * Handles loading images from Homebase drives.
 *
 * Note: Caching is now delegated to Coil3. This class serves as a data fetcher.
 */
class HomebaseImageLoader(
    private val driveFileProvider: DriveFileProvider,
    private val fileOperationsProvider: FileOperationsProvider,
    private val peerFileProvider: PeerFileByGlobalTransitProvider,
) {
    // Durable scope hosting full-payload loads so a cancelled caller (e.g. a
    // prefetch whose grid item left composition) does not abort a load that
    // other callers are awaiting.
    private val cacheScope = supervisedScope("image-loader")
    private val fullPayloadCache = FullPayloadByteCache(
        maxBytes = MAX_FULL_PAYLOAD_CACHE_BYTES,
        scope = cacheScope,
    )

    companion object {
        private const val TAG = "HomebaseImageLoader"

        // In-memory budget for cached decrypted full-payload bytes (compressed,
        // ~a handful of photos). Small next to Coil's decoded-bitmap cache.
        private const val MAX_FULL_PAYLOAD_CACHE_BYTES = 48L * 1024 * 1024

        // Content types whose payload is rendered as-is, bypassing the
        // thumbnail pipeline. GIF stays here because we want the
        // animated original, not a static raster frame. SVG used to be
        // here because the sender side wrapped SVG bytes verbatim as a
        // "thumbnail" — receivers had to load the payload directly
        // since Coil has no SVG decoder. Phase 3 rasterizes SVG to webp
        // thumbnails on the sender side, so the receiver now prefers
        // those real bitmaps just like any other format.
        val THUMBLESS_CONTENT_TYPES = setOf("image/gif")

        // Default retry configuration for image loading.
        // retryOn unwraps the RuntimeException wrapper produced below so the real
        // cause (e.g. NotFoundException) still short-circuits retries.
        val DEFAULT_RETRY_CONFIG = RetryConfig(
            maxRetries = 3,
            initialDelayMs = 500L,
            maxDelayMs = 5000L,
            backoffMultiplier = 2.0,
            retryOn = { e -> (e.cause ?: e) !is NotFoundException }
        )
    }

    /** Decode embedded preview thumbnail from base64 */
//    @OptIn(ExperimentalEncodingApi::class)
//    fun decodePreviewThumbnail(data: HomebaseImageData): CachedImage? {
//        val preview = data.previewThumbnail ?: return null
//        return try {
//            val bytes = Base64.Default.decode(preview.content)
//            CachedImage(
//                bytes = bytes,
//                contentType = preview.contentType,
//                size = ImageSize(preview.pixelWidth, preview.pixelHeight)
//            )
//        } catch (e: Exception) {
//            Logger.e(TAG) { "Failed to decode preview thumbnail: ${e.message}" }
//            null
//        }
//    }

    /**
     * Load thumbnail at the requested size with automatic retry on failure.
     *
     * @param data Image data containing drive/file identifiers
     * @param targetSize Target thumbnail size
     * @param retryConfig Optional custom retry configuration
     */
    suspend fun loadThumbnail(
        data: HomebaseImageData,
        targetSize: ImageSize,
        retryConfig: RetryConfig = DEFAULT_RETRY_CONFIG
    ): CachedImage? {
        // Check pending file first - no retry needed for local files
        if (data.isPending) {
            return fileOperationsProvider.loadPendingFile(data)
        }

        // Skip thumbnail fetch for GIF (load the animated original instead).
        // Use effectiveContentType — for a GIF the previewThumbnail is a tiny
        // WebP, so contentTypeHint alone is "image/webp" and would wrongly try
        // to fetch a server thumbnail that was never generated (N=0 thumbs for
        // GIFs), surfacing as a NotFoundException / error triangle.
        if (data.effectiveContentType in THUMBLESS_CONTENT_TYPES) {
            return loadFullPayload(data, retryConfig)
        }

        // Snap the measured display size to the best native thumbnail the server
        // actually stores. Keying the request (and thus the disk cache) by the
        // native size deduplicates entries across measured sizes/devices and lets
        // the read hit what the sender seeded under the optimistic fileId.
        val nativeSize = selectThumbSize(targetSize, data.availableThumbSizes)

        // Fetch from server with retry
        return withRetry(retryConfig, TAG) {
            val response = try {
                if (data.isOverPeer) {
                    // Followed-post media lives on the author's drive: read the thumbnail over peer
                    // by globalTransitId. On 404 (no server thumb) the caller keeps the embedded
                    // preview — no regression.
                    peerFileProvider.getThumbOverPeerByGlobalTransitId(
                        peer = data.remoteOdinId!!,
                        driveId = data.driveId,
                        globalTransitId = data.globalTransitId!!,
                        payloadKey = data.payloadKey,
                        width = nativeSize.pixelWidth,
                        height = nativeSize.pixelHeight,
                        keyHeader = data.keyHeader,
                    )
                } else {
                    driveFileProvider.getThumbBytesDecrypted(
                        driveId = data.driveId,
                        fileId = data.fileId,
                        payloadKey = data.payloadKey,
                        keyHeader = data.keyHeader,
                        width = nativeSize.pixelWidth,
                        height = nativeSize.pixelHeight,
                        lastModified = data.lastModified,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RuntimeException(
                    buildImageLoadFailureMessage(
                        kind = "thumb",
                        driveId = data.driveId,
                        fileId = data.fileId,
                        payloadKey = data.payloadKey,
                        size = nativeSize,
                        lastModified = data.lastModified,
                        causeClass = e::class.simpleName
                    ),
                    e
                )
            } ?: return@withRetry null

            CachedImage(
                bytes = response.bytes, contentType = response.contentType, size = nativeSize
            )
        }
    }

    /**
     * Load full resolution payload with automatic retry on failure.
     *
     * @param data Image data containing drive/file identifiers
     * @param retryConfig Optional custom retry configuration
     */
    suspend fun loadFullPayload(
        data: HomebaseImageData, retryConfig: RetryConfig = DEFAULT_RETRY_CONFIG
    ): CachedImage? {
        // Check pending file first - no retry needed for local files
        if (data.isPending) {
            return fileOperationsProvider.loadPendingFile(data)
        }

        // Serve from (and populate) the in-memory cache. Concurrent callers for
        // the same image — the base painter, the subsampling tiler, a prefetch —
        // coalesce onto a single fetch+decrypt instead of repeating it.
        return fullPayloadCache.getOrLoad(fullPayloadCacheKey(data)) {
            fetchFullPayloadUncached(data, retryConfig)
        }
    }

    /**
     * Drop all cached decrypted full-payload bytes. Wire to the same triggers as
     * the Coil memory cache (manual "Clear cache", logout/account switch).
     */
    suspend fun clearMemoryCache() {
        fullPayloadCache.clear()
    }

    /**
     * Non-suspending best-effort clear for call sites that aren't coroutines
     * (e.g. the logout hook). Schedules the clear on [cacheScope] so decrypted
     * bytes for the outgoing user don't linger in RAM.
     */
    fun clearMemoryCacheAsync() {
        cacheScope.launch { fullPayloadCache.clear() }
    }

    private fun fullPayloadCacheKey(data: HomebaseImageData): String =
        "${data.remoteOdinId?.let { "$it@" } ?: ""}${data.driveId}/${data.fileId}/${data.payloadKey}/${data.lastModified ?: 0}"

    private suspend fun fetchFullPayloadUncached(
        data: HomebaseImageData, retryConfig: RetryConfig
    ): CachedImage? {
        return withRetry(retryConfig, TAG) {
            val response = try {
                if (data.isOverPeer) {
                    peerFileProvider.getPayloadOverPeerByGlobalTransitId(
                        peer = data.remoteOdinId!!,
                        driveId = data.driveId,
                        globalTransitId = data.globalTransitId!!,
                        payloadKey = data.payloadKey,
                        keyHeader = data.keyHeader,
                    )
                } else {
                    driveFileProvider.getPayloadBytesDecrypted(
                        driveId = data.driveId,
                        fileId = data.fileId,
                        key = data.payloadKey,
                        keyHeader = data.keyHeader,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PayloadTooLargeException) {
                // Above the render limit (#845): the full-res upgrade is refused
                // deterministically — retrying can't shrink the payload. Returning
                // null keeps the already-rendered thumbnail on screen instead of
                // OOM-ing on a huge image.
                Logger.w(tag = TAG) {
                    "full payload too large to render (${e.sizeBytes} > ${e.limitBytes}) — " +
                        "keeping thumbnail for ${data.fileId}/${data.payloadKey}"
                }
                return@withRetry null
            } catch (e: Exception) {
                throw RuntimeException(
                    buildImageLoadFailureMessage(
                        kind = "payload",
                        driveId = data.driveId,
                        fileId = data.fileId,
                        payloadKey = data.payloadKey,
                        size = null,
                        lastModified = data.lastModified,
                        causeClass = e::class.simpleName
                    ),
                    e
                )
            } ?: return@withRetry null

            CachedImage(
                bytes = response.bytes,
                contentType = response.contentType,
                size = null // null indicates full resolution
            )
        }
    }
}

/**
 * Request-level context string appended to image-load failures so the retry
 * log identifies which drive/file/size failed. Shared by [HomebaseImageLoader]
 * and covered by unit tests.
 */
internal fun buildImageLoadFailureMessage(
    kind: String,
    driveId: Uuid,
    fileId: Uuid,
    payloadKey: String,
    size: ImageSize?,
    lastModified: Long?,
    causeClass: String?,
): String {
    val sizeStr = size?.let { "${it.pixelWidth}x${it.pixelHeight}" } ?: "full"
    return "$kind load failed drive=$driveId file=$fileId key=$payloadKey " +
        "size=$sizeStr lastMod=$lastModified cause=$causeClass"
}

/** Load pending/local file from filesystem */
suspend fun FileOperationsProvider.loadPendingFile(data: HomebaseImageData): CachedImage? {
    val fileUri = data.pendingFileUri ?: return null
    return try {
        val file = platformFileFromPath(fileUri)
        val bytes = this.readFileBytes(fileUri)
        val contentType = file.contentType()
        CachedImage(bytes = bytes, contentType = contentType, size = null)
    } catch (e: Exception) {
        Logger.e(tag = "HomebaseImageLoader") { "Failed to load pending file: ${e.message}" }
        null
    }
}
