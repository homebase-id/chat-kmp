package id.homebase.api.image

import id.homebase.api.file.FileOperationsProvider
import org.jetbrains.skia.Image

internal actual fun openImageSource(bytes: ByteArray): ImageSource = SkiaImageSource(bytes)

internal actual fun openImageFile(path: String, fileOps: FileOperationsProvider): ImageSource? = null

internal actual fun resampleNatively(image: Image, width: Int, height: Int): Image? = null
