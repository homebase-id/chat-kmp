@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.image

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.Pixmap
import platform.Accelerate.kvImageNoError
import platform.Accelerate.kvImageNoFlags
import platform.Accelerate.vImageClipToAlpha_RGBA8888
import platform.Accelerate.vImageScale_ARGB8888
import platform.Accelerate.vImage_Buffer

/**
 * Accelerate's default Lanczos-3, straight from the source's raster pixels into the result's. Both
 * functions only care that alpha comes last, so they serve Skia's RGBA and BGRA alike.
 */
internal actual suspend fun resampleRaster(image: Image, width: Int, height: Int): Image {
    val info = image.imageInfo
    check(info.colorType == ColorType.RGBA_8888 || info.colorType == ColorType.BGRA_8888) { "vImage needs 8-bit pixels, not ${info.colorType}" }
    check(info.colorInfo.alphaType != ColorAlphaType.UNPREMUL) { "vImage needs premultiplied pixels" }
    val source = checkNotNull(image.peekPixels()) { "The pipeline only makes raster images" }
    val bitmap = Bitmap()
    var destination: Pixmap? = null
    try {
        check(bitmap.allocPixels(info.withWidthHeight(width, height))) { "Couldn't allocate $width x $height pixels" }
        val to = checkNotNull(bitmap.peekPixels()).also { destination = it }
        memScoped {
            val fromBuffer = buffer(source)
            val toBuffer = buffer(to)
            checkVImage(vImageScale_ARGB8888(fromBuffer.ptr, toBuffer.ptr, null, kvImageNoFlags), "scale")
            if (!info.isOpaque) checkVImage(vImageClipToAlpha_RGBA8888(toBuffer.ptr, toBuffer.ptr, kvImageNoFlags), "clip to alpha")
        }
        bitmap.setImmutable()
        return Image.makeFromBitmap(bitmap)
    } finally {
        destination?.close()
        source.close()
        bitmap.close()
    }
}

private fun MemScope.buffer(pixmap: Pixmap) = alloc<vImage_Buffer> {
    data = interpretCPointer<ByteVar>(pixmap.addr)
    width = pixmap.info.width.convert()
    height = pixmap.info.height.convert()
    rowBytes = pixmap.rowBytes.convert()
}

private fun checkVImage(error: Long, step: String) =
    check(error == kvImageNoError.toLong()) { "vImage couldn't $step: error $error" }
