package id.homebase.chat.services.image

import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
