@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.chat.services.image.StickerImageProcessor
import id.homebase.chat.services.image.isBackgroundRemovalSupported
import id.homebase.chat.services.image.removeBackground
import id.homebase.resources.MR
import id.homebase.resources.cd_sticker_variant_cutout
import id.homebase.resources.cd_sticker_variant_original
import id.homebase.resources.chat_sticker_save_failed
import id.homebase.resources.chat_sticker_saved
import id.homebase.resources.chat_sticker_send_failed
import id.homebase.resources.chat_sticker_variant_cutout
import id.homebase.resources.chat_sticker_variant_original
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "StickerCreator"

/**
 * A renderable sticker variant offered in the chooser. Each carries its own display label and
 * accessibility description, so adding a variant is a single enum entry — the chooser UI
 * iterates [StickerCreateState.Choose.variants] without a per-kind `when`.
 */
enum class StickerVariant(val label: StringResource, val contentDescription: StringResource) {
    CutOut(MR.string.chat_sticker_variant_cutout, MR.string.cd_sticker_variant_cutout),
    Original(MR.string.chat_sticker_variant_original, MR.string.cd_sticker_variant_original),
}

/** One selectable option in the chooser: its rendered bytes + content type. */
data class StickerVariantOption(
    val kind: StickerVariant,
    val bytes: ByteArray,
    val contentType: String,
) {
    override fun equals(other: Any?) = this === other || (other is StickerVariantOption &&
        kind == other.kind && contentType == other.contentType && bytes.contentEquals(other.bytes))
    override fun hashCode() = (kind.hashCode() * 31 + contentType.hashCode()) * 31 + bytes.contentHashCode()
}

/** Transient state for the create-a-sticker chooser. null = not creating. */
sealed interface StickerCreateState {
    data object Processing : StickerCreateState
    data class Choose(
        val conversationId: Uuid,
        val variants: List<StickerVariantOption>, // 1..N options in display order
        val selected: StickerVariant,
    ) : StickerCreateState
}

/**
 * Create-a-sticker chooser flow (cut-out+outline vs original; extensible to more variants)
 * behind injectable seams. Confirm saves to the library AND sends to the conversation, with
 * the bytes normalized once so both copies are byte-identical. All heavy image work runs off
 * the main thread.
 */
class StickerCreator(
    private val scope: CoroutineScope,
    private val saveSticker: suspend (bytes: ByteArray, contentType: String) -> Uuid?,
    private val sendSticker: suspend (conversationId: Uuid, bytes: ByteArray, contentType: String) -> Unit,
    private val sendInfo: (StringResource) -> Unit,
    private val awaitDriveGranted: suspend () -> Unit,
    private val isTransparent: (ByteArray) -> Boolean = ImageUtils::hasNonOpaquePixels,
    private val bgRemovalSupported: () -> Boolean = ::isBackgroundRemovalSupported,
    // removeBackground only — addWhiteOutline caps the working size internally, so no separate downscale here.
    private val cutOut: suspend (ByteArray) -> ByteArray? = { removeBackground(it) },
    private val addOutline: suspend (ByteArray) -> ByteArray = { StickerImageProcessor.addWhiteOutline(it) },
    private val normalize: suspend (ByteArray, String) -> Pair<ByteArray, String> = { bytes, contentType ->
        withContext(Dispatchers.Default) {
            if (ImageFormatDetector.isHeic(bytes)) {
                val jpeg = convertHeicToJpeg(bytes)
                if (jpeg != null) jpeg to "image/jpeg" else bytes to contentType
            } else bytes to contentType
        }
    },
    // Where the heavy image work (decode/transparency probe/segmenter/outline) runs. Default
    // off the main thread; tests inject the test dispatcher so advanceUntilIdle stays deterministic.
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<StickerCreateState?>(null)
    val state: StateFlow<StickerCreateState?> = _state.asStateFlow()
    private var job: Job? = null

    fun create(bytes: ByteArray, contentType: String, conversationId: Uuid) {
        job?.cancel()
        job = scope.launch { runCreate(bytes, contentType, conversationId) }
    }

    private suspend fun runCreate(bytes: ByteArray, contentType: String, conversationId: Uuid) {
        try {
            _state.value = StickerCreateState.Processing // show the spinner before any heavy decode
            // All heavy work off the main thread: the transparency probe alone is a full image
            // decode + readPixels, which would otherwise freeze the UI (and the spinner) on a
            // large pick.
            val outlined: ByteArray? = withContext(workDispatcher) {
                when {
                    isTransparent(bytes) -> addOutline(bytes)
                    bgRemovalSupported() -> cutOut(bytes)?.let { addOutline(it) }
                    else -> null
                }
            }
            val variants = buildList {
                if (outlined != null) add(StickerVariantOption(StickerVariant.CutOut, outlined, "image/png"))
                add(StickerVariantOption(StickerVariant.Original, bytes, contentType))
            }
            Logger.d(tag = TAG) {
                "create: cutOutOffered=${outlined != null} cutOut=${outlined?.size ?: 0}B original=${bytes.size}B $contentType"
            }
            _state.value = StickerCreateState.Choose(conversationId, variants, variants.first().kind)
        } catch (e: CancellationException) {
            _state.value = null; throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Sticker create failed" }
            _state.value = null
            sendInfo(MR.string.chat_sticker_save_failed)
        }
    }

    fun selectVariant(variant: StickerVariant) {
        val s = _state.value as? StickerCreateState.Choose ?: return
        if (s.variants.none { it.kind == variant }) return
        _state.value = s.copy(selected = variant)
    }

    fun confirm() {
        val s = _state.value as? StickerCreateState.Choose ?: return
        val opt = s.variants.firstOrNull { it.kind == s.selected } ?: return
        Logger.d(tag = TAG) { "confirm: variant=${s.selected} bytes=${opt.bytes.size}B ${opt.contentType}" }
        _state.value = null
        scope.launch {
            try {
                val (bytes, contentType) = normalize(opt.bytes, opt.contentType)
                awaitDriveGranted()
                val saved = saveSticker(bytes, contentType)
                sendSticker(s.conversationId, bytes, contentType) // suspend; a failure throws → caught below
                sendInfo(if (saved != null) MR.string.chat_sticker_saved else MR.string.chat_sticker_save_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Confirm sticker create failed" }
                sendInfo(MR.string.chat_sticker_send_failed)
            }
        }
    }

    fun dismiss() {
        job?.cancel(); job = null
        _state.value = null
    }
}
