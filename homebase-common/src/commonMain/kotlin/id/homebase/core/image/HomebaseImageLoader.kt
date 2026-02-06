package id.homebase.core.image

import co.touchlab.kermit.Logger
import id.homebase.api.client.RetryConfig
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.withRetry
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.readBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
class HomebaseImageLoader(private val driveFileProvider: DriveFileProvider) {
    companion object {
        private const val TAG = "HomebaseImageLoader"

        // Content types that don't need thumbnails (render as-is)
        val THUMBLESS_CONTENT_TYPES = setOf("image/svg+xml", "image/gif")

        // Default retry configuration for image loading
        val DEFAULT_RETRY_CONFIG = RetryConfig(
            maxRetries = 3, initialDelayMs = 500L, maxDelayMs = 5000L, backoffMultiplier = 2.0
        )
    }

    // unused code
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
            return loadPendingFile(data)
        }

        // Skip thumbnail fetch for SVG/GIF (load full payload instead)
        if (data.contentTypeHint in THUMBLESS_CONTENT_TYPES) {
            return loadFullPayload(data, retryConfig)
        }

        // Fetch from server with retry
        return withRetry(retryConfig, TAG) {
            val response = driveFileProvider.getThumbBytesDecrypted(
                driveId = data.driveId,
                fileId = data.fileId,
                payloadKey = data.payloadKey,
                keyHeader = data.keyHeader,
                width = targetSize.pixelWidth,
                height = targetSize.pixelHeight,
                lastModified = data.lastModified,
            ) ?: return@withRetry null

            CachedImage(
                bytes = response.bytes, contentType = response.contentType, size = targetSize
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
            return loadPendingFile(data)
        }

        // Fetch from server with retry
        return withRetry(retryConfig, TAG) {
            val response = driveFileProvider.getPayloadBytesDecrypted(
                driveId = data.driveId,
                fileId = data.fileId,
                key = data.payloadKey,
                keyHeader = data.keyHeader
            ) ?: return@withRetry null

            CachedImage(
                bytes = response.bytes,
                contentType = response.contentType,
                size = null // null indicates full resolution
            )
        }
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
}
