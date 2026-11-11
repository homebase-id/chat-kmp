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
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.Pixmap
import platform.Accelerate.kvImageHighQualityResampling
import platform.Accelerate.kvImageNoError
import platform.Accelerate.kvImageNoFlags
import platform.Accelerate.vImageClipToAlpha_RGBA8888
import platform.Accelerate.vImageScale_ARGB8888
import platform.Accelerate.vImage_Buffer

/**
 * Accelerate's Lanczos, straight from the source's raster pixels into the result's. Both functions
 * only care that alpha comes last, so they serve Skia's RGBA and BGRA alike.
 */
internal actual fun resampleNatively(image: Image, width: Int, height: Int): Image? {
    val info = image.imageInfo
    if (info.colorType != ColorType.RGBA_8888 && info.colorType != ColorType.BGRA_8888) return null
    val source = checkNotNull(image.peekPixels()) { "The pipeline only makes raster images" }
    val bitmap = Bitmap()
    try {
        check(bitmap.allocPixels(info.withWidthHeight(width, height))) { "Couldn't allocate $width x $height pixels" }
        val destination = checkNotNull(bitmap.peekPixels())
        try {
            memScoped {
                val from = buffer(source)
                val to = buffer(destination)
                checkVImage(vImageScale_ARGB8888(from.ptr, to.ptr, null, kvImageHighQualityResampling), "scale")
                if (!info.isOpaque) checkVImage(vImageClipToAlpha_RGBA8888(to.ptr, to.ptr, kvImageNoFlags), "clip to alpha")
            }
        } finally {
            destination.close()
        }
        bitmap.setImmutable()
        return Image.makeFromBitmap(bitmap)
    } finally {
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
