package id.homebase.api.image

import id.homebase.api.lib.image.ImageFormatDetector
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

private val BILINEAR: SamplingMode = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

internal class SkiaPipelineBitmap(private val image: Image) : PipelineBitmap {
    override val width get() = image.width
    override val height get() = image.height

    override fun scaledTo(width: Int, height: Int): PipelineBitmap {
        val surface = rasterSurface(image, width, height)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(width.toFloat(), height.toFloat()),
            BILINEAR,
            null,
            true,
        )
        return SkiaPipelineBitmap(surface.makeImageSnapshot())
    }

    override fun encode(format: ImageFormat, quality: Int): ByteArray =
        image.encodeToData(ImageUtils.encodedFormatFor(format), quality)?.bytes
            ?: throw IllegalStateException("Failed to encode image as $format")

    override fun close() = image.close()
}

/** Skia reads EXIF rotation itself and decodes lazily, so the size costs no pixels. */
internal class SkiaImageSource(bytes: ByteArray) : ImageSource {
    private val image: Image = Image.makeFromEncoded(
        if (ImageFormatDetector.isHeic(bytes)) {
            convertHeicToJpeg(bytes) ?: throw IllegalArgumentException("Failed to convert HEIC to JPEG")
        } else {
            bytes
        },
    )

    override val displayedSize get() = ImageSize(image.width, image.height)

    // Drawn into a raster so the decode happens exactly here, and only here.
    override fun decode(minLongEdge: Int): PipelineBitmap {
        val surface = rasterSurface(image, image.width, image.height)
        surface.canvas.drawImage(image, 0f, 0f)
        return SkiaPipelineBitmap(surface.makeImageSnapshot())
    }

    override fun close() = image.close()
}

// Taking the source's colour space is what keeps a Display P3 photo from being relabelled sRGB.
private fun rasterSurface(source: Image, width: Int, height: Int): Surface =
    Surface.makeRaster(ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL, source.colorSpace))
