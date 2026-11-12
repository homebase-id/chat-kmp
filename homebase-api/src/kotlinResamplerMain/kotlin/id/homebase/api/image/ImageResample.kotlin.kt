package id.homebase.api.image

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual suspend fun resampleRaster(image: Image, width: Int, height: Int): Image {
    val bitmap = Bitmap()
    val pixels = try {
        bitmap.allocPixels(info(image, image.width, image.height))
        check(image.readPixels(bitmap, 0, 0)) { "Couldn't read the image's pixels" }
        bitmap.readPixels() ?: throw IllegalStateException("Couldn't read the image's pixels")
    } finally {
        bitmap.close()
    }
    return Image.makeRaster(info(image, width, height), resampleLanczos(pixels, image.width, image.height, width, height), width * 4)
}

private fun info(image: Image, width: Int, height: Int) =
    ImageInfo(width, height, ColorType.N32, image.pipelineAlphaType, image.colorSpace)
