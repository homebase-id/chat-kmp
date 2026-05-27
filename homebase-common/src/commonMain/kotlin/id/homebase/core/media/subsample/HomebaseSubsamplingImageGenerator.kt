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
    ): SubsamplingImageGenerateResult {
        val model = result.request.data
        if (model !is HomebaseImageData) {
            return SubsamplingImageGenerateResult.Error("Not a HomebaseImageData model")
        }
        val payload = this.imageLoader.loadFullPayload(model)
            ?: return SubsamplingImageGenerateResult.Error("Failed to load payload bytes")
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
