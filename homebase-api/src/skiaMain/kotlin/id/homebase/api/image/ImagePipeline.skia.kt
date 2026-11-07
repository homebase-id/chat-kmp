package id.homebase.api.image

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

internal class SkiaPipelineBitmap(private val image: Image) : PipelineBitmap {
    override val width get() = image.width
    override val height get() = image.height

    override fun scaledTo(width: Int, height: Int): PipelineBitmap {
        val surface = rasterSurface(image, width, height)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(width.toFloat(), height.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
        return SkiaPipelineBitmap(surface.snapshotAndClose())
    }

    override fun encode(format: ImageFormat, quality: Int): ByteArray {
        val data = image.encodeToData(ImageUtils.encodedFormatFor(format), quality)
            ?: throw IllegalStateException("Failed to encode image as $format")
        try {
            return data.bytes
        } finally {
            data.close()
        }
    }

    override fun close() = image.close()
}

/** Skia reads EXIF rotation itself and decodes lazily, so the size costs no pixels. */
internal class SkiaImageSource(bytes: ByteArray) : ImageSource {
    private val image: Image = ImageUtils.decodeImage(bytes)

    override val displayedSize get() = ImageSize(image.width, image.height)

    // Drawn into a raster so the decode happens exactly here, and only here.
    override fun decode(minLongEdge: Int): PipelineBitmap {
        val surface = rasterSurface(image, image.width, image.height)
        surface.canvas.drawImage(image, 0f, 0f)
        return SkiaPipelineBitmap(surface.snapshotAndClose())
    }

    override fun close() = image.close()
}

// The snapshot keeps its own reference, and until the surface closes, every step's pixels stay alive.
private fun Surface.snapshotAndClose(): Image = try {
    makeImageSnapshot()
} finally {
    close()
}

// Taking the source's colour space is what keeps a Display P3 photo from being relabelled sRGB.
private fun rasterSurface(source: Image, width: Int, height: Int): Surface =
    Surface.makeRaster(ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL, source.colorSpace))
