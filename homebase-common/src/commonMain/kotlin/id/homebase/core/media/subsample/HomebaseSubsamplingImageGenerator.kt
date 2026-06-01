package id.homebase.core.media.subsample

import androidx.compose.ui.graphics.painter.Painter
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.SuccessResult
import com.github.panpf.zoomimage.compose.coil.CoilComposeSubsamplingImageGenerator
import com.github.panpf.zoomimage.subsampling.ImageSource
import com.github.panpf.zoomimage.subsampling.SubsamplingImage
import com.github.panpf.zoomimage.subsampling.SubsamplingImageGenerateResult
import com.github.panpf.zoomimage.subsampling.fromByteArray
import com.github.panpf.zoomimage.subsampling.toFactory
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader

class HomebaseSubsamplingImageGenerator(
    private val imageLoader: HomebaseImageLoader,
) : CoilComposeSubsamplingImageGenerator {

    override suspend fun generateImage(
        context: PlatformContext,
        imageLoader: ImageLoader,
        result: SuccessResult,
        painter: Painter,
    ): SubsamplingImageGenerateResult? {
        val model = result.request.data
        if (model !is HomebaseImageData) {
            return null
        }
        // Returning null (not Error) on a transient load failure lets the base
        // image stand on its own and keeps the generator chain non-fatal; a
        // later load can re-run subsampling instead of being marked failed.
        val payload = this.imageLoader.loadFullPayload(model)
            ?: return null
        // GIFs (and any thumbless type) are the animated original and can't be
        // tiled. The embedded preview's contentType is always webp, so detect
        // the real type from the loaded payload (not model.contentTypeHint).
        // Returning null defers to the library's animation-aware generators
        // rather than building a tile source the size/type gates would reject.
        if (payload.contentType in HomebaseImageLoader.THUMBLESS_CONTENT_TYPES) {
            return null
        }
        val imageSource = ImageSource.fromByteArray(payload.bytes)
        return SubsamplingImageGenerateResult.Success(SubsamplingImage(imageSource.toFactory(), null))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other != null && this::class == other::class
    }

    override fun hashCode(): Int = this::class.hashCode()

    override fun toString(): String = "HomebaseSubsamplingImageGenerator"
}
