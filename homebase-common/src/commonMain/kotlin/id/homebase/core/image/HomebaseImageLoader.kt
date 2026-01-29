package id.homebase.core.image

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DriveFileProvider
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.readBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cache entry storing loaded image bytes with size metadata */
data class CachedImage(val bytes: ByteArray, val contentType: String, val size: ImageSize?) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as CachedImage
        return bytes.contentEquals(other.bytes) &&
                contentType == other.contentType &&
                size == other.size
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (size?.hashCode() ?: 0)
        return result
    }
}

/**
 * Handles loading images from Homebase drives with caching support.
 *
 * Supports progressive loading:
 * 1. Decode embedded tinyThumb (base64) for instant preview
 * 2. Load server thumbnail at requested size
 * 3. Optionally load full payload for maximum resolution
 *
 * Implements "larger cached image" optimization: if a larger version is already cached, it will be
 * used instead of fetching smaller.
 */
class HomebaseImageLoader(
        private val driveFileProvider: DriveFileProvider,
        private val maxCacheSize: Int = DEFAULT_CACHE_SIZE
) {
    companion object {
        private const val TAG = "HomebaseImageLoader"
        private const val DEFAULT_CACHE_SIZE = 50

        // Content types that don't need thumbnails (render as-is)
        val THUMBLESS_CONTENT_TYPES = setOf("image/svg+xml", "image/gif")
    }

    private val cache = mutableMapOf<String, CachedImage>()
    private val cacheOrder = mutableListOf<String>() // Track insertion order for LRU
    private val cacheMutex = Mutex()

    /** Decode embedded preview thumbnail from base64 */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodePreviewThumbnail(data: HomebaseImageData): CachedImage? {
        val preview = data.previewThumbnail ?: return null
        return try {
            val bytes = Base64.Default.decode(preview.content)
            CachedImage(
                    bytes = bytes,
                    contentType = preview.contentType,
                    size = ImageSize(preview.pixelWidth, preview.pixelHeight)
            )
        } catch (e: Exception) {
            Logger.e(TAG) { "Failed to decode preview thumbnail: ${e.message}" }
            null
        }
    }

    /** Load thumbnail at the requested size. First checks cache for larger existing version. */
    suspend fun loadThumbnail(data: HomebaseImageData, targetSize: ImageSize): CachedImage? {
        // Check pending file first
        if (data.isPending) {
            return loadPendingFile(data)
        }

        // Check if we have a larger cached version
        val cached = findLargerCachedImage(data, targetSize)
        if (cached != null) {
            Logger.d(TAG) { "Using larger cached image for ${data.fileId}" }
            return cached
        }

        // Skip thumbnail fetch for SVG/GIF (load full payload instead)
        if (data.contentTypeHint in THUMBLESS_CONTENT_TYPES) {
            return loadFullPayload(data)
        }

        // Fetch from server
        return try {
            val response =
                    driveFileProvider.getThumbBytesDecrypted(
                            driveId = data.driveId,
                            fileId = data.fileId,
                            payloadKey = data.payloadKey,
                            width = targetSize.pixelWidth,
                            height = targetSize.pixelHeight,
                            lastModified = data.lastModified
                    )
                            ?: return null

            val cachedImage =
                    CachedImage(
                            bytes = response.bytes,
                            contentType = response.contentType,
                            size = targetSize
                    )

            putCache(cacheKey(data, targetSize), cachedImage)
            cachedImage
        } catch (e: Exception) {
            Logger.e(TAG) { "Failed to load thumbnail: ${e.message}" }
            null
        }
    }

    /** Load full resolution payload */
    suspend fun loadFullPayload(data: HomebaseImageData): CachedImage? {
        // Check pending file first
        if (data.isPending) {
            return loadPendingFile(data)
        }

        // Check cache for full payload
        val fullKey = cacheKey(data, size = null)
        val cached = getCache(fullKey)
        if (cached != null) {
            return cached
        }

        return try {
            val response =
                    driveFileProvider.getPayloadBytesDecrypted(
                            driveId = data.driveId,
                            fileId = data.fileId,
                            key = data.payloadKey
                    )
                            ?: return null

            val cachedImage =
                    CachedImage(
                            bytes = response.bytes,
                            contentType = response.contentType,
                            size = null // null indicates full resolution
                    )

            putCache(fullKey, cachedImage)
            cachedImage
        } catch (e: Exception) {
            Logger.e(TAG) { "Failed to load full payload: ${e.message}" }
            null
        }
    }

    /**
     * Find larger cached image than requested size. Full payload (size=null) is considered largest.
     */
    suspend fun findLargerCachedImage(
            data: HomebaseImageData,
            requestedSize: ImageSize?
    ): CachedImage? =
            cacheMutex.withLock {
                val keyPrefix = cacheKeyPrefix(data)

                cache.entries
                        .filter { it.key.startsWith(keyPrefix) }
                        .filter { entry ->
                            val cachedSize = entry.value.size
                            // Full payload (null size) is always larger
                            if (cachedSize == null) return@filter true
                            // Check if cached size >= requested
                            cachedSize.isLargerOrEqualTo(requestedSize)
                        }
                        .maxByOrNull { it.value.size?.pixelCount ?: Int.MAX_VALUE }
                        ?.value
            }

    /** Load pending/local file from filesystem */
    private suspend fun loadPendingFile(data: HomebaseImageData): CachedImage? {
        val file = data.pendingFile ?: return null

        return try {
            val bytes = file.readBytes()
            val contentType = file.mimeType()?.toString() ?: file.extension
            CachedImage(bytes = bytes, contentType = contentType, size = null)
        } catch (e: Exception) {
            Logger.e(TAG) { "Failed to load pending file: ${e.message}" }
            null
        }
    }

    /** Clear cached entry for a specific image */
    suspend fun invalidateCache(data: HomebaseImageData) =
            cacheMutex.withLock {
                val keyPrefix = cacheKeyPrefix(data)
                cache.keys.removeAll { it.startsWith(keyPrefix) }
            }

    /** Clear all cached images */
    suspend fun clearCache() = cacheMutex.withLock { cache.clear() }

    // Cache key includes drive, file, payload key, and size
    private fun cacheKeyPrefix(data: HomebaseImageData): String =
            "${data.driveId}:${data.fileId}:${data.payloadKey}"

    private fun cacheKey(data: HomebaseImageData, size: ImageSize?): String {
        val prefix = cacheKeyPrefix(data)
        return if (size != null) {
            "$prefix:${size.pixelWidth}x${size.pixelHeight}"
        } else {
            "$prefix:full"
        }
    }

    private suspend fun getCache(key: String): CachedImage? = cacheMutex.withLock { cache[key] }

    private suspend fun putCache(key: String, image: CachedImage) =
            cacheMutex.withLock {
                // Update access order
                cacheOrder.remove(key)
                cacheOrder.add(key)

                // Evict oldest if at capacity
                while (cache.size >= maxCacheSize && cacheOrder.isNotEmpty()) {
                    val oldest = cacheOrder.removeFirst()
                    cache.remove(oldest)
                }
                cache[key] = image
            }


}
