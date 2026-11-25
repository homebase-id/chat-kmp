package id.homebase.core.image

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import id.homebase.api.image.convertHeicToJpeg
import okio.use
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use

/**
 * Coil3 Decoder that handles HEIC/HEIF images on Desktop (JVM).
 * Uses the existing FFmpeg-based convertHeicToJpeg, then decodes the
 * resulting JPEG via Skia's standard pipeline.
 */
class HeicDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val heicBytes = source.source().use { it.readByteArray() }
        val jpegBytes = convertHeicToJpeg(heicBytes) ?: return null
        val image = try {
            Image.makeFromEncoded(jpegBytes)
        } catch (e: Exception) {
            Logger.e(tag = TAG) { "Skia failed to decode converted JPEG (${jpegBytes.size} bytes): ${e.message}" }
            return null
        }
        return image.use { decodeForDisplay(it, options) }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isHeicSource(result)) return null
            return HeicDecoder(result.source, options)
        }
    }

    private companion object {
        private const val TAG = "HeicDecoder"
    }
}
