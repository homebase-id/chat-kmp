@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import id.homebase.api.image.ImageUtils
import id.homebase.chat.services.image.StickerImageProcessor
import id.homebase.chat.services.image.isBackgroundRemovalSupported
import id.homebase.chat.services.image.removeBackground
import id.homebase.resources.MR
import id.homebase.resources.chat_sticker_save_failed
import id.homebase.resources.chat_sticker_saved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "StickerCreator"

enum class StickerVariant { CutOut, Original }

sealed interface StickerCreateState {
    data object Processing : StickerCreateState
    data class Choose(
        val conversationId: Uuid,
        val cutOutOutlined: ByteArray?,
        val original: ByteArray,
        val originalContentType: String,
        val selected: StickerVariant,
    ) : StickerCreateState {
        override fun equals(other: Any?) = this === other || (other is Choose &&
            conversationId == other.conversationId && selected == other.selected &&
            original.contentEquals(other.original) && originalContentType == other.originalContentType &&
            (if (cutOutOutlined == null) other.cutOutOutlined == null
             else other.cutOutOutlined != null && cutOutOutlined.contentEquals(other.cutOutOutlined)))
        override fun hashCode(): Int {
            var r = conversationId.hashCode(); r = 31 * r + selected.hashCode()
            r = 31 * r + original.contentHashCode(); r = 31 * r + (cutOutOutlined?.contentHashCode() ?: 0)
            return r
        }
    }
}

/**
 * Create-a-sticker chooser flow (cut-out+outline vs original) behind injectable seams.
 * Confirm saves to the library AND sends to the conversation.
 */
class StickerCreator(
    private val scope: CoroutineScope,
    private val saveSticker: suspend (bytes: ByteArray, contentType: String) -> Uuid?,
    private val sendSticker: (conversationId: Uuid, bytes: ByteArray, contentType: String) -> Unit,
    private val sendInfo: (StringResource) -> Unit,
    private val awaitDriveGranted: suspend () -> Unit,
    private val isTransparent: (ByteArray) -> Boolean = ImageUtils::hasNonOpaquePixels,
    private val bgRemovalSupported: () -> Boolean = ::isBackgroundRemovalSupported,
    private val cutOut: suspend (ByteArray) -> ByteArray? = {
        removeBackground(it)?.let { png -> StickerImageProcessor.downscaleCutOut(png) }
    },
    private val addOutline: suspend (ByteArray) -> ByteArray = { StickerImageProcessor.addWhiteOutline(it) },
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
            val outlined: ByteArray? = when {
                isTransparent(bytes) -> addOutline(bytes)
                bgRemovalSupported() -> {
                    _state.value = StickerCreateState.Processing
                    cutOut(bytes)?.let { addOutline(it) }
                }
                else -> null
            }
            _state.value = StickerCreateState.Choose(
                conversationId = conversationId,
                cutOutOutlined = outlined,
                original = bytes,
                originalContentType = contentType,
                selected = if (outlined != null) StickerVariant.CutOut else StickerVariant.Original,
            )
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
        if (variant == StickerVariant.CutOut && s.cutOutOutlined == null) return
        _state.value = s.copy(selected = variant)
    }

    fun confirm() {
        val s = _state.value as? StickerCreateState.Choose ?: return
        val (bytes, contentType) = when (s.selected) {
            StickerVariant.CutOut -> (s.cutOutOutlined ?: return) to "image/png"
            StickerVariant.Original -> s.original to s.originalContentType
        }
        _state.value = null
        scope.launch {
            try {
                awaitDriveGranted()
                val saved = saveSticker(bytes, contentType)
                sendSticker(s.conversationId, bytes, contentType)
                sendInfo(if (saved != null) MR.string.chat_sticker_saved else MR.string.chat_sticker_save_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Confirm sticker create failed" }
                sendInfo(MR.string.chat_sticker_save_failed)
            }
        }
    }

    fun dismiss() {
        job?.cancel(); job = null
        _state.value = null
    }
}
