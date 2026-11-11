package id.homebase.api.image

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

internal class SkiaPipelineBitmap(private val image: Image) : PipelineBitmap {
    override val width get() = image.width
    override val height get() = image.height
    override val isSrgb get() = image.colorSpace.let { it == null || it.isSRGB }

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

    override suspend fun resampledTo(width: Int, height: Int): PipelineBitmap =
        SkiaPipelineBitmap(resampleRaster(image, width, height))

    override fun encode(format: ImageFormat, quality: Int): ByteArray {
        val data = image.encodeToData(ImageUtils.encodedFormatFor(format), quality)
            ?: throw IllegalStateException("Failed to encode image as $format")
        try {
            return data.bytes
        } finally {
            data.close()
        }
    }

    override fun inSrgb(): PipelineBitmap {
        if (isSrgb) return this
        val surface = Surface.makeRaster(ImageInfo(image.width, image.height, ColorType.N32, image.pipelineAlphaType, ColorSpace.sRGB))
        surface.canvas.drawImage(image, 0f, 0f)
        return SkiaPipelineBitmap(surface.snapshotAndClose())
    }

    override fun close() = image.close()
}

/** [PipelineBitmap.resampledTo] for one of the pipeline's raster images: 8-bit, opaque or premultiplied. */
internal expect suspend fun resampleRaster(image: Image, width: Int, height: Int): Image

// Opaque only when the decoder says so, which lets an opaque photo skip the clamp to alpha.
internal val Image.pipelineAlphaType: ColorAlphaType
    get() = if (alphaType == ColorAlphaType.OPAQUE) ColorAlphaType.OPAQUE else ColorAlphaType.PREMUL

/** Skia reads EXIF rotation itself and decodes lazily, so the size costs no pixels. */
internal class SkiaImageSource(bytes: ByteArray) : ImageSource {
    private val image: Image = ImageUtils.decodeImage(bytes)

    override val displayedSize get() = ImageSize(image.width, image.height)

    // readPixels decodes exactly here, and unlike drawing, it reports a body that can't be decoded.
    override fun decode(minLongEdge: Int): PipelineBitmap {
        val pixels = Bitmap()
        try {
            pixels.allocPixels(ImageInfo(image.width, image.height, ColorType.N32, image.pipelineAlphaType, image.colorSpace))
            check(image.readPixels(pixels, 0, 0)) { "Couldn't decode the image's pixels" }
            pixels.setImmutable()
            return SkiaPipelineBitmap(Image.makeFromBitmap(pixels))
        } finally {
            pixels.close()
        }
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
    Surface.makeRaster(ImageInfo(width, height, ColorType.N32, source.pipelineAlphaType, source.colorSpace))
