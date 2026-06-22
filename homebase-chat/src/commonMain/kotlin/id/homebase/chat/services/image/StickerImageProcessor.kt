package id.homebase.chat.services.image

import co.touchlab.kermit.Logger
import id.homebase.api.image.ArgbImage
import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "StickerImageProcessor"

/**
 * Sizing/encoding for the background-remover sticker pipeline.
 *
 * Kept separate from [removeBackground] (the platform segmenter) and from the editor
 * handler so the pure `ByteArray -> ByteArray` transform is unit-testable on JVM — the
 * JVM target of `homebase-api` is backed by Skia [ImageUtils], so the resize/encode runs
 * for real in `jvmTest`.
 */
object StickerImageProcessor {

    /**
     * Max edge (px) of the persisted/uploaded cut-out. The chat render-side caps a
     * sticker at `Dimens.Sticker.maxSize` (160.dp ≈ ≤480px even at 3x density), so 512
     * gives crisp 2× display headroom while staying a tiny fraction of a full-resolution
     * camera frame. The send path uploads images BYTE-FOR-BYTE (see
     * [id.homebase.chat.services.builder.MessageAttachmentBuilder]), so this directly
     * bounds upload/storage/download cost. Mirrors the 512px sticker convention used by
     * Signal / WhatsApp / Telegram.
     */
    const val STICKER_MAX_DIM = 512

    // PNG is lossless so quality is a no-op for the encoder, but the API requires it.
    private const val STICKER_PNG_QUALITY = 100

    // Alpha above this counts as "the subject" when building the outline mask.
    private const val OUTLINE_ALPHA_THRESHOLD = 16
    // Outline thickness as a fraction of the (capped) longest edge. ~4–5% reads as a clean
    // WhatsApp-style halo without looking heavy.
    private const val OUTLINE_RADIUS_FRACTION = 0.045f

    /**
     * Adds a WhatsApp-style opaque-white outline around the alpha shape of a transparent
     * cut-out. Caps the working size to [STICKER_MAX_DIM] (so cost is independent of the
     * caller's input resolution), pads the canvas by the outline radius (so the halo around
     * a frame-filling subject isn't clipped), computes a chamfer distance transform from the
     * subject (O(w*h), round halo), paints pixels within the radius opaque white, and
     * composites the original on top. Pure PNG-in/PNG-out on Dispatchers.Default; returns the
     * input unchanged on any failure so it can never block sticker creation.
     */
    suspend fun addWhiteOutline(cutOutPng: ByteArray, radiusPx: Int = -1): ByteArray =
        withContext(Dispatchers.Default) {
            try {
                // Bound the working size; resizePreserveAspect never upscales, so an already
                // small cut-out is just re-encoded.
                val capped = ImageUtils.resizePreserveAspect(
                    srcBytes = cutOutPng,
                    maxWidth = STICKER_MAX_DIM, maxHeight = STICKER_MAX_DIM,
                    outputFormat = ImageFormat.PNG, quality = STICKER_PNG_QUALITY,
                ).bytes.takeIf { it.isNotEmpty() } ?: cutOutPng

                val img = ImageUtils.decodeToArgb(capped)
                if (img == null) {
                    Logger.w(tag = TAG) { "addWhiteOutline: decodeToArgb returned null — returning un-outlined cut-out" }
                    return@withContext cutOutPng
                }
                val sw = img.width; val sh = img.height
                if (sw == 0 || sh == 0) {
                    Logger.w(tag = TAG) { "addWhiteOutline: zero dimensions — returning un-outlined cut-out" }
                    return@withContext cutOutPng
                }
                val r = if (radiusPx >= 0) radiusPx
                    else maxOf(1, (maxOf(sw, sh) * OUTLINE_RADIUS_FRACTION).toInt())

                // Pad by r on all sides so the halo has room (0 = transparent).
                val pw = sw + 2 * r; val ph = sh + 2 * r
                val padded = IntArray(pw * ph)
                for (y in 0 until sh) {
                    val srcRow = y * sw; val dstRow = (y + r) * pw + r
                    for (x in 0 until sw) padded[dstRow + x] = img.pixels[srcRow + x]
                }

                // Chamfer 3-4 distance from the subject. Orthogonal step = 3, diagonal = 4;
                // a pixel within r px of the subject has dist <= r*3 (round, approx-Euclidean).
                val inf = Int.MAX_VALUE / 4
                val dist = IntArray(pw * ph) {
                    if ((padded[it] ushr 24 and 0xFF) > OUTLINE_ALPHA_THRESHOLD) 0 else inf
                }
                for (y in 0 until ph) for (x in 0 until pw) {
                    val i = y * pw + x
                    if (dist[i] == 0) continue
                    var d = dist[i]
                    if (x > 0) d = minOf(d, dist[i - 1] + 3)
                    if (y > 0) d = minOf(d, dist[i - pw] + 3)
                    if (x > 0 && y > 0) d = minOf(d, dist[i - pw - 1] + 4)
                    if (x < pw - 1 && y > 0) d = minOf(d, dist[i - pw + 1] + 4)
                    dist[i] = d
                }
                for (y in ph - 1 downTo 0) for (x in pw - 1 downTo 0) {
                    val i = y * pw + x
                    var d = dist[i]
                    if (x < pw - 1) d = minOf(d, dist[i + 1] + 3)
                    if (y < ph - 1) d = minOf(d, dist[i + pw] + 3)
                    if (x < pw - 1 && y < ph - 1) d = minOf(d, dist[i + pw + 1] + 4)
                    if (x > 0 && y < ph - 1) d = minOf(d, dist[i + pw - 1] + 4)
                    dist[i] = d
                }

                val threshold = r * 3
                val white = 0xFFFFFFFF.toInt()
                val out = IntArray(pw * ph)
                for (i in padded.indices) {
                    val a = padded[i] ushr 24 and 0xFF
                    out[i] = when {
                        a == 0xFF -> padded[i]                                      // solid subject
                        dist[i] <= threshold ->                                     // halo
                            if (a > 0) alphaOver(padded[i], white) else white
                        else -> padded[i]                                           // outside halo
                    }
                }
                val outlinedPng = ImageUtils.encodeArgbToPng(ArgbImage(out, pw, ph))
                val finalPng = if (maxOf(pw, ph) > STICKER_MAX_DIM) downscaleCutOut(outlinedPng) else outlinedPng
                Logger.d(tag = TAG) {
                    "addWhiteOutline: applied r=$r ${sw}x$sh -> ${pw}x$ph (in=${cutOutPng.size}B out=${finalPng.size}B)"
                }
                finalPng
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "addWhiteOutline FAILED — returning un-outlined cut-out (in=${cutOutPng.size}B)" }
                cutOutPng
            }
        }

    /**
     * Tightly crop a transparent cut-out to its alpha bounding box (plus a small margin) so the
     * subject fills the sticker frame instead of being a tiny island in a full-frame transparent
     * canvas. The segmenters emit a full-frame masked image, so without this the subject gets
     * downscaled to 512 along with its transparent margins and renders small.
     *
     * Runs BEFORE [addWhiteOutline] so the outline radius and the final 512 cap are sized to the
     * subject. Preserves natural aspect (no forced square). Pure PNG-in/PNG-out on
     * Dispatchers.Default; returns the input unchanged on decode failure / fully-transparent input
     * / any error so it can never block sticker creation.
     */
    suspend fun cropToSubject(cutOutPng: ByteArray, marginFraction: Float = 0.04f): ByteArray =
        withContext(Dispatchers.Default) {
            try {
                val img = ImageUtils.decodeToArgb(cutOutPng) ?: return@withContext cutOutPng
                val w = img.width; val h = img.height
                if (w == 0 || h == 0) return@withContext cutOutPng

                var minX = w; var minY = h; var maxX = -1; var maxY = -1
                val px = img.pixels
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        if ((px[row + x] ushr 24 and 0xFF) > OUTLINE_ALPHA_THRESHOLD) {
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                    }
                }
                if (maxX < minX || maxY < minY) {
                    // Fully transparent (no subject) — nothing to crop to.
                    return@withContext cutOutPng
                }

                val bw = maxX - minX + 1; val bh = maxY - minY + 1
                // Small margin keeps the later white-outline halo from being clipped at the edge.
                val pad = (maxOf(bw, bh) * marginFraction).roundToInt()
                val x0 = (minX - pad).coerceAtLeast(0)
                val y0 = (minY - pad).coerceAtLeast(0)
                val x1 = (maxX + pad).coerceAtMost(w - 1)
                val y1 = (maxY + pad).coerceAtMost(h - 1)
                val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
                if (cw == w && ch == h) return@withContext cutOutPng // already tight

                val cropped = IntArray(cw * ch)
                for (y in 0 until ch) {
                    val srcRow = (y0 + y) * w + x0
                    val dstRow = y * cw
                    px.copyInto(cropped, dstRow, srcRow, srcRow + cw)
                }
                val out = ImageUtils.encodeArgbToPng(ArgbImage(cropped, cw, ch))
                Logger.d(tag = TAG) { "cropToSubject: ${w}x$h -> ${cw}x$ch (bbox ${bw}x$bh, pad=$pad)" }
                out
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "cropToSubject FAILED — returning un-cropped cut-out (in=${cutOutPng.size}B)" }
                cutOutPng
            }
        }

    /** Source-over composite of [fg] (0xAARRGGBB) onto [bg]. */
    private fun alphaOver(fg: Int, bg: Int): Int {
        val fa = fg ushr 24 and 0xFF
        if (fa == 0) return bg
        if (fa == 0xFF) return fg
        val ba = bg ushr 24 and 0xFF
        val outA = fa + ba * (255 - fa) / 255
        fun ch(sh: Int): Int {
            val f = fg ushr sh and 0xFF; val b = bg ushr sh and 0xFF
            val v = (f * fa + b * ba * (255 - fa) / 255) / maxOf(1, outA)
            return v.coerceIn(0, 255)
        }
        return (outA shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Downscale the segmenter's full-resolution cut-out to a `<= [STICKER_MAX_DIM]`px
     * **lossless PNG** (alpha preserved) for upload.
     *
     * PNG, not WebP: the Android [ImageUtils] WebP branch is `WEBP_LOSSY` and gated to
     * API 30+, but `minSdk` is 28 — WebP output would `NoSuchFieldError`-crash on API
     * 28/29. PNG encodes on every API level and keeps cut-out edges alpha-perfect.
     *
     * Never upscales (an already-small cut-out is just re-encoded). Defensive: returns
     * the original [cutOutBytes] unchanged if the re-encode fails or yields nothing, so a
     * resize hiccup can never block sending the cut-out. CPU-bound, so it hops to
     * [Dispatchers.Default].
     */
    suspend fun downscaleCutOut(cutOutBytes: ByteArray): ByteArray =
        withContext(Dispatchers.Default) {
            // runCatching catches Throwable, so a resize failure — or, hypothetically, an
            // API-gated encoder on an old device — degrades to the original cut-out instead
            // of crashing. It wraps only the synchronous resize (not a suspension point), so
            // it cannot swallow CancellationException; cancellation propagates via withContext.
            runCatching {
                ImageUtils.resizePreserveAspect(
                    srcBytes = cutOutBytes,
                    maxWidth = STICKER_MAX_DIM,
                    maxHeight = STICKER_MAX_DIM,
                    outputFormat = ImageFormat.PNG,
                    quality = STICKER_PNG_QUALITY,
                ).bytes.takeIf { it.isNotEmpty() }
            }.getOrNull() ?: cutOutBytes
        }
}
