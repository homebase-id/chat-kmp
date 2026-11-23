package id.homebase.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.SizeResolver
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.Base64Bytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Decodes tinies into Coil's memory cache off the main thread, before the list that shows them composes. */
class TinyPreviewWarmer(private val imageLoader: ImageLoader, private val context: PlatformContext) {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Leaves Coil's other decode slots to the thumbnails on screen.
    private val decodes = Semaphore(2)

    /** [previews] in display order: returns once the first [awaited] are in memory, and decodes the rest in the background. */
    suspend fun warm(previews: List<Base64Bytes>, awaited: Int = AWAITED_PREVIEWS) = withContext(Dispatchers.Default) {
        val misses = previews.distinct().map { previewRequest(context, it) }.filter { imageLoader.cachedPreview(it) == null }
        misses.take(awaited).forEach { launch { decode(it) } }
        misses.drop(awaited).forEach { background.launch { decode(it) } }
    }

    // Checked again under the permit: an earlier list's background decode may have got there first.
    private suspend fun decode(request: ImageRequest) = decodes.withPermit {
        if (imageLoader.cachedPreview(request) == null) imageLoader.execute(request)
    }
}

// About two screens of rows, so the first frame and the list's prefetch find their tinies.
private const val AWAITED_PREVIEWS = 24

/** Every tiny a file's views may draw: each payload's own, and the file's, which payloads without one fall back to. */
fun tinyPreviews(payloads: List<PayloadDescriptor>?, previewThumbnail: EmbeddedThumb?): List<Base64Bytes> =
    payloads.orEmpty().mapNotNull { it.previewThumbnail?.content } + listOfNotNull(previewThumbnail?.content)

internal fun ImageLoader.cachedPreview(request: ImageRequest) = memoryCache?.get(MemoryCache.Key(request.memoryCacheKey!!))?.image

internal fun previewRequest(context: PlatformContext, bytes: Base64Bytes): ImageRequest =
    ImageRequest.Builder(context).data(bytes.bytes).memoryCacheKey("preview/" + bytes.toBase64()).size(SizeResolver.ORIGINAL).build()
