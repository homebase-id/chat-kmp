package id.homebase.core.image

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
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
 * The animated-vs-static decision is made in [Factory.create], not in [decode]:
 * create() parses the Skia [Codec] up front and returns this decoder ONLY for a
 * genuinely multi-frame GIF/WebP. Single-frame GIF/WebP — crucially including
 * the static WebP *preview thumbnail* that every image ships (server thumbnails
 * are always WebP) — make create() return null, so Coil falls through to its
 * default (static) Skia decoder.
 *
 * Why the check MUST live in create() and not decode(): a null from a
 * Decoder.Factory.create() makes Coil try the next factory (fall through to the
 * default decoder); a null from a *chosen* Decoder.decode() does NOT fall
 * through — Coil treats it as a decode failure and the image breaks. Returning
 * null from decode() for static WebP thumbnails was exactly that bug — all image
 * previews broke on Desktop/iOS, while full JPEG/PNG payloads (never claimed
 * here) kept working.
 */
class AnimatedSkiaDecoder(
    private val codec: Codec,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        // codec is guaranteed multi-frame here (Factory.create only returns this
        // decoder when frameCount > 1). SkiaAnimatedImage takes ownership of the
        // codec and closes it when it is itself closed.
        val frameCount = codec.frameCount
        val framesInfo = codec.framesInfo
        val durations = IntArray(frameCount) { i -> framesInfo.getOrNull(i)?.duration ?: 0 }
        val required = IntArray(frameCount) { i ->
            // skiko reports the index of the frame this one composites onto, or a
            // negative sentinel for a standalone keyframe. Normalize anything out
            // of range to -1 (keyframe / decode standalone).
            val r = framesInfo.getOrNull(i)?.requiredFrame ?: -1
            if (r in 0 until frameCount) r else -1
        }
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
            // Cheap container sniff first — never parse JPEG/PNG/HEIC.
            if (!isAnimatableSource(result)) return null

            // Read the bytes WITHOUT consuming the source (peek), so that if this
            // turns out to be static we can decline and Coil's default decoder
            // still has the full source to read.
            val bytes = try {
                result.source.source().peek().readByteArray()
            } catch (e: Throwable) {
                Logger.d(tag = TAG) { "peek read failed: ${e.message}" }
                return null
            }
            if (bytes.isEmpty()) return null

            val codec = try {
                Codec.makeFromData(Data.makeFromBytes(bytes))
            } catch (e: Throwable) {
                // Not decodable as an animated container — let the default decoder try.
                Logger.d(tag = TAG) { "Codec.makeFromData failed (${bytes.size} bytes): ${e.message}" }
                return null
            }

            // Single-frame GIF/WebP (incl. every static WebP preview thumbnail):
            // decline so Coil falls through to its default static Skia decoder.
            // Only genuinely animated sources are claimed by this decoder.
            if (codec.frameCount <= 1) {
                codec.close()
                return null
            }
            return AnimatedSkiaDecoder(codec)
        }
    }

    private companion object {
        private const val TAG = "AnimatedSkiaDecoder"
    }
}
