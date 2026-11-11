package id.homebase.api.image

import org.jetbrains.skia.Image

internal actual fun openImageSource(bytes: ByteArray): ImageSource = SkiaImageSource(bytes)

internal actual fun openImageFile(path: String): ImageSource? = null

internal actual fun resampleNatively(image: Image, width: Int, height: Int): Image? = null
