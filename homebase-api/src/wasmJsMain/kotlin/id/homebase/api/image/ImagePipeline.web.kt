package id.homebase.api.image

internal actual fun openImageSource(bytes: ByteArray): ImageSource = SkiaImageSource(bytes)
