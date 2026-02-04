package id.homebase.core.image

import androidx.compose.runtime.key
import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DriveFileProvider
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.readBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException

/** Image data container */
// TODO: Rename to memoryImage?
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
 * Handles loading images from Homebase drives.
 *
 * Note: Caching is now delegated to Coil3. This class serves as a data fetcher.
 */
class HomebaseImageLoader(private val driveFileProvider: DriveFileProvider) {
    companion object {
        private const val TAG = "HomebaseImageLoader"

        // Content types that don't need thumbnails (render as-is)
        val THUMBLESS_CONTENT_TYPES = setOf("image/svg+xml", "image/gif")
    }

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

    /** Load thumbnail at the requested size. */
    suspend fun loadThumbnail(data: HomebaseImageData, targetSize: ImageSize): CachedImage? {
        // Check pending file first
        if (data.isPending) {
            return loadPendingFile(data)
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
                    keyHeader = data.keyHeader,
                    width = targetSize.pixelWidth,
                    height = targetSize.pixelHeight,
                    lastModified = data.lastModified,
                )
                    ?: return null

            CachedImage(
                bytes = response.bytes,
                contentType = response.contentType,
                size = targetSize
            )
        } catch (e: CancellationException) {
            // Expected when composable leaves composition - rethrow to let Coil handle it
            throw e
        } catch (e: Exception) {
            Logger.e(TAG) { "Failed to load thumbnail: ${e.message}" }
            null
        }
    }

    /** Load full resolution payload */
    suspend fun loadFullPayload(data: HomebaseImageData): CachedImage? {
        // Check pending file first
        if (data.isPending) {
            return loadPendingFile(data) // TODO: <-- This seems unnecessary
        }

        return try {
            val response =
                driveFileProvider.getPayloadBytesDecrypted(
                    driveId = data.driveId,
                    fileId = data.fileId,
                    key = data.payloadKey,
                    keyHeader = data.keyHeader
                )
                    ?: return null

            CachedImage(
                bytes = response.bytes,
                contentType = response.contentType,
                size = null // null indicates full resolution
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG) { "Failed to load full payload: ${e.message}" }
            null
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
