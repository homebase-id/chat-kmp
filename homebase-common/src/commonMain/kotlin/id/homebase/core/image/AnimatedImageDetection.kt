package id.homebase.core.image

import coil3.fetch.SourceFetchResult
import id.homebase.api.file.contentTypeFromBytes

/**
 * Shared "could this be an animated image?" gate for the in-house Skia
 * animated decoder (Desktop/JVM, iOS/native, Web/wasmJs).
 *
 * This is intentionally a *container* check, not an "is animated" check: it
 * answers "are these bytes a GIF or WebP whose frames the Skia codec might
 * animate?". A GIF/WebP with a single frame still passes this gate — the
 * [AnimatedSkiaDecoder] then asks the Skia codec for the real frame count and
 * returns null for single-frame images so Coil falls back to the static
 * decoder. Keeping the cheap container sniff here means the
 * expensive codec parse only runs for GIF/WebP, never for JPEG/PNG/HEIC.
 *
 * Animated GIF and animated WebP are the only animated raster formats Skia's
 * [org.jetbrains.skia.Codec] exposes multi-frame; APNG is not reliably
 * multi-frame across skiko versions, so we don't claim PNG here.
 */
fun isAnimatableSource(result: SourceFetchResult): Boolean {
    when (result.mimeType) {
        "image/gif", "image/webp" -> return true
        // Any other declared image type is not an animated container we handle.
        // (mimeType is null only when the fetcher couldn't determine it; fall
        // through to the magic-byte sniff below.)
        null -> Unit
        else -> return false
    }
    val header = result.peekHeader(12) ?: return false
    return contentTypeFromBytes(header).let { it == "image/gif" || it == "image/webp" }
}
