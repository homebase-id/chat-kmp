package id.homebase.api.image

import androidx.compose.ui.graphics.ImageBitmap
import id.homebase.api.image.draw.StrokeCommand

actual fun convertHeicToJpeg(heicBytes: ByteArray): ByteArray? = null

actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    TODO("Not yet implemented")
}

actual object ImageUtils {
    actual fun resizePreserveAspect(
        srcBytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun compressOnly(
        srcBytes: ByteArray,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun crop(
        srcBytes: ByteArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun rotate(
        srcBytes: ByteArray,
        degrees: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun getNaturalSize(srcBytes: ByteArray): ImageSize {
        TODO("Not yet implemented")
    }

    actual fun warpAffine(
        srcBytes: ByteArray,
        matrix9: FloatArray,
        outputWidth: Int,
        outputHeight: Int,
        fillColorArgb: Int,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun drawStrokes(
        srcBytes: ByteArray,
        strokes: List<StrokeCommand>,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        TODO("Not yet implemented")
    }
}