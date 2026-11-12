package id.homebase.api.image

import id.homebase.api.file.FileOperationsProvider

internal actual fun openImageSource(bytes: ByteArray): ImageSource = SkiaImageSource(bytes)

internal actual fun openImageFile(path: String, fileOps: FileOperationsProvider): ImageSource? = null
