package id.homebase.core.image

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.use
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/**
 * In-house Coil3 [Decoder] that animates multi-frame GIF and WebP via Skia's
 * [org.jetbrains.skia.Codec] on the non-Android targets (Desktop/JVM,
 * iOS/native, Web/wasmJs). Coil ships no animated decoder for these targets —
 * coil-gif's AnimatedImageDecoder is Android-only and there is no
 * `coil3.gif.AnimatedSkiaImageDecoder` at any version — so we implement it here
 * using skiko, which all three targets bundle. Android keeps using coil-gif
 * (PR #663) and is intentionally not wired to this decoder.
 *
 * Registered next to [HeicDecoder] in each non-Android AppModule, so it sits in
 * the existing encrypted Coil pipeline: it decodes the already-fetched,
 * already-decrypted payload bytes Coil hands it and returns an animating
 * [coil3.Image]. It does not fetch, decrypt, or cache anything itself.
 *
 * Single-frame GIFs/WebP return null from [decode] so Coil falls through to its
 * default (static) Skia decoder — the static path is left completely unchanged.
 */
class AnimatedSkiaDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = source.source().use { it.readByteArray() }
        if (bytes.isEmpty()) return null

        val codec = try {
            Codec.makeFromData(Data.makeFromBytes(bytes))
        } catch (e: Throwable) {
            // Not decodable as an animated container — let the default decoder try.
            Logger.d(tag = TAG) { "Codec.makeFromData failed (${bytes.size} bytes): ${e.message}" }
            return null
        }

        val frameCount = codec.frameCount
        if (frameCount <= 1) {
            // Static GIF/WebP — defer to Coil's default Skia decoder unchanged.
            codec.close()
            return null
        }

        val framesInfo = codec.framesInfo
        val durations = IntArray(frameCount) { i -> framesInfo.getOrNull(i)?.duration ?: 0 }
        val required = IntArray(frameCount) { i ->
            // skiko reports the index of the frame this one composites onto, or a
            // negative sentinel for a standalone keyframe. Normalize anything
            // out of range to -1 (keyframe / decode standalone).
            val r = framesInfo.getOrNull(i)?.requiredFrame ?: -1
            if (r in 0 until frameCount) r else -1
        }

        // codec keeps ownership of the underlying frames; SkiaAnimatedImage owns
        // codec from here and closes it when it is itself closed.
        val image = SkiaAnimatedImage(
            codec = codec,
            frameDurationsMs = durations,
            requiredFrames = required,
            repetitionCount = codec.repetitionCount,
        )
        // The first paint comes from the embedded thumbnail placeholder until the
        // driver advances; mark not sampled (we decode at native resolution).
        return DecodeResult(image = image, isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isAnimatableSource(result)) return null
            return AnimatedSkiaDecoder(result.source, options)
        }
    }

    private companion object {
        private const val TAG = "AnimatedSkiaDecoder"
    }
}
