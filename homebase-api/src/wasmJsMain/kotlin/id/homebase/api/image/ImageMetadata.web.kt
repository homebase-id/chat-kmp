package id.homebase.api.image

actual fun readImageMetadata(srcBytes: ByteArray): ImageMetadata? = readJpegExifCamera(srcBytes)
