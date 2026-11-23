package id.homebase.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.SizeResolver
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.Base64Bytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decodes tinies into Coil's memory cache off the main thread, before the list that shows them composes,
 * so a [HomebaseImage] finds its tiny in memory and the main thread only paints it.
 */
class TinyPreviewWarmer(private val imageLoader: ImageLoader, private val context: PlatformContext) {

    /** Returns once every tiny not already in memory has been decoded (or failed). */
    suspend fun warm(previews: List<Base64Bytes>) = withContext(Dispatchers.Default) {
        for (bytes in previews.distinct()) {
            val request = previewRequest(context, bytes)
            if (imageLoader.cachedPreview(request) == null) launch { imageLoader.execute(request) }
        }
    }
}

/** Every tiny a file's views may draw: each payload's own, and the file's, which payloads without one fall back to. */
fun tinyPreviews(payloads: List<PayloadDescriptor>?, previewThumbnail: EmbeddedThumb?): List<Base64Bytes> =
    payloads.orEmpty().mapNotNull { it.previewThumbnail?.content } + listOfNotNull(previewThumbnail?.content)

internal fun ImageLoader.cachedPreview(request: ImageRequest) = memoryCache?.get(MemoryCache.Key(request.memoryCacheKey!!))?.image

internal fun previewRequest(context: PlatformContext, bytes: Base64Bytes): ImageRequest =
    ImageRequest.Builder(context).data(bytes.bytes).memoryCacheKey("preview/" + bytes.toBase64()).size(SizeResolver.ORIGINAL).build()
