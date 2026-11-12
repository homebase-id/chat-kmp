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
        SkiaPipelineBitmap(resampleNatively(image, width, height) ?: resampledInKotlin(width, height))

    private suspend fun resampledInKotlin(width: Int, height: Int): Image {
        val pixels = resampleLanczos(readPixels(), image.width, image.height, width, height)
        return Image.makeRaster(info(width, height), pixels, width * 4)
    }

    private fun readPixels(): ByteArray {
        val bitmap = Bitmap()
        try {
            bitmap.allocPixels(info(image.width, image.height))
            check(image.readPixels(bitmap, 0, 0)) { "Couldn't read the image's pixels" }
            return bitmap.readPixels() ?: throw IllegalStateException("Couldn't read the image's pixels")
        } finally {
            bitmap.close()
        }
    }

    private fun info(width: Int, height: Int) = ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL, image.colorSpace)

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
        val surface = Surface.makeRaster(ImageInfo(image.width, image.height, ColorType.N32, ColorAlphaType.PREMUL, ColorSpace.sRGB))
        surface.canvas.drawImage(image, 0f, 0f)
        return SkiaPipelineBitmap(surface.snapshotAndClose())
    }

    override fun close() = image.close()
}

/**
 * [PipelineBitmap.resampledTo] between the platform's own pixel buffers, or null where the platform
 * has nothing better than the shared Kotlin Lanczos, which works on Kotlin heap copies.
 */
internal expect fun resampleNatively(image: Image, width: Int, height: Int): Image?

/** Skia reads EXIF rotation itself and decodes lazily, so the size costs no pixels. */
internal class SkiaImageSource(private val bytes: ByteArray) : ImageSource {
    private val image: Image = ImageUtils.decodeImage(bytes)

    override val displayedSize get() = ImageSize(image.width, image.height)

    // readPixels decodes exactly here, and unlike drawing, it reports a body that can't be decoded.
    override fun decode(minLongEdge: Int): PipelineBitmap {
        val pixels = Bitmap()
        try {
            pixels.allocPixels(ImageInfo(image.width, image.height, ColorType.N32, ColorAlphaType.PREMUL, image.colorSpace))
            check(image.readPixels(pixels, 0, 0)) { "Couldn't decode the image's pixels" }
            pixels.setImmutable()
            return SkiaPipelineBitmap(Image.makeFromBitmap(pixels))
        } finally {
            pixels.close()
        }
    }

    override fun metadata(): ImageMetadata? = readImageMetadata(bytes)

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
