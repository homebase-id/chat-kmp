package id.homebase.core.image

import coil3.fetch.SourceFetchResult

/**
 * Shared "could this be an animated image?" gate for the in-house Skia
 * animated decoder (Desktop/JVM, iOS/native, Web/wasmJs).
 *
 * This is intentionally a *container* check, not an "is animated" check: it
 * answers "are these bytes a GIF or WebP whose frames the Skia codec might
 * animate?". A GIF/WebP with a single frame still passes this gate — the
 * [AnimatedSkiaDecoder] then asks the Skia codec for the real frame count and
 * returns null for single-frame images so Coil falls back to its default
 * (static) Skia decoder. Keeping the cheap container sniff here means the
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
    return try {
        val peek = result.source.source().peek()
        val header = ByteArray(HEADER_PEEK_BYTES)
        val read = peek.read(header)
        if (read < MIN_HEADER_BYTES) return false
        isGifHeader(header) || isWebpHeader(header)
    } catch (_: Exception) {
        false
    }
}

/** GIF magic: "GIF87a" or "GIF89a" — only the first three bytes ("GIF") matter here. */
private fun isGifHeader(b: ByteArray): Boolean =
    b.size >= 3 &&
        b[0] == 0x47.toByte() && // G
        b[1] == 0x49.toByte() && // I
        b[2] == 0x46.toByte() //    F

/**
 * WebP magic: "RIFF" .... "WEBP" — bytes 0..3 == RIFF, bytes 8..11 == WEBP.
 * Bytes 4..7 are the little-endian RIFF chunk size (skipped).
 */
private fun isWebpHeader(b: ByteArray): Boolean =
    b.size >= 12 &&
        b[0] == 0x52.toByte() && b[1] == 0x49.toByte() && // RI
        b[2] == 0x46.toByte() && b[3] == 0x46.toByte() && // FF
        b[8] == 0x57.toByte() && b[9] == 0x45.toByte() && // WE
        b[10] == 0x42.toByte() && b[11] == 0x50.toByte() // BP

// Enough to cover the WebP RIFF/WEBP split header (12 bytes); GIF only needs 3.
private const val HEADER_PEEK_BYTES = 12
private const val MIN_HEADER_BYTES = 3
