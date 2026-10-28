package id.homebase.core.notifications

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Any Skia-decodable image (WebP, JPEG, GIF's first frame, …) as PNG; [circle] crops to the inscribed circle. */
internal fun encodePng(bytes: ByteArray, circle: Boolean): ByteArray? {
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    if (!circle) return image.encodeToData(EncodedImageFormat.PNG)?.bytes
    val size = minOf(image.width, image.height)
    val surface = Surface.makeRasterN32Premul(size, size)
    val left = (image.width - size) / 2f
    val top = (image.height - size) / 2f
    surface.canvas.clipRRect(RRect.makeXYWH(0f, 0f, size.toFloat(), size.toFloat(), size / 2f), antiAlias = true)
    surface.canvas.drawImageRect(
        image,
        Rect.makeXYWH(left, top, size.toFloat(), size.toFloat()),
        Rect.makeWH(size.toFloat(), size.toFloat()),
    )
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
}
