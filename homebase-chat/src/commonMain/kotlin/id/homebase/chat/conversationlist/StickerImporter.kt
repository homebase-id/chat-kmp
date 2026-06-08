@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import id.homebase.api.image.ImageUtils
import id.homebase.chat.services.image.isBackgroundRemovalSupported
import id.homebase.chat.services.image.removeBackground
import id.homebase.resources.MR
import id.homebase.resources.chat_sticker_import_no_subject
import id.homebase.resources.chat_sticker_import_not_transparent
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

private const val TAG = "StickerImporter"

/** Transient UI state for the smart-import confirm dialog. `null` = no dialog. */
sealed interface StickerImportPreview {
    /** Background-removal is running for an opaque image. */
    data object Processing : StickerImportPreview

    /** The cut-out is ready; the dialog shows it with Use / Cancel. */
    data class Ready(val bytes: ByteArray, val contentType: String) : StickerImportPreview {
        override fun equals(other: Any?) =
            this === other || (other is Ready && bytes.contentEquals(other.bytes) && contentType == other.contentType)
        override fun hashCode() = 31 * bytes.contentHashCode() + contentType.hashCode()
    }
}

/**
 * Smart sticker-import decision tree (spec Option C), behind injectable seams so every
 * branch is unit-testable without Compose, platform code, or real image bytes:
 *  - transparent image  -> save directly,
 *  - opaque + supported  -> background-remove -> [preview] Ready -> [confirm] save,
 *  - opaque + unsupported / no subject -> soft-fail info message, nothing saved.
 *
 * The drive-grant gate is awaited before every save (both paths).
 */
class StickerImporter(
    private val scope: CoroutineScope,
    private val saveSticker: suspend (bytes: ByteArray, contentType: String) -> Uuid?,
    private val sendInfo: (StringResource) -> Unit,
    private val awaitDriveGranted: suspend () -> Unit,
    private val isTransparent: (ByteArray) -> Boolean = ImageUtils::hasNonOpaquePixels,
    private val bgRemovalSupported: () -> Boolean = ::isBackgroundRemovalSupported,
    private val cutOut: suspend (ByteArray) -> ByteArray? = ::removeBackground,
) {
    private val _preview = MutableStateFlow<StickerImportPreview?>(null)
    val preview: StateFlow<StickerImportPreview?> = _preview.asStateFlow()

    private var job: Job? = null

    /** Entry point for a freshly-picked image. */
    fun import(bytes: ByteArray, contentType: String) {
        job?.cancel()
        job = scope.launch { runImport(bytes, contentType) }
    }

    private suspend fun runImport(bytes: ByteArray, contentType: String) {
        try {
            if (isTransparent(bytes)) {
                awaitDriveGranted()
                saveAndReport(bytes, contentType)
                return
            }
            if (!bgRemovalSupported()) {
                sendInfo(MR.string.chat_sticker_import_not_transparent)
                return
            }
            _preview.value = StickerImportPreview.Processing
            val cut = cutOut(bytes)
            if (cut == null) {
                _preview.value = null
                sendInfo(MR.string.chat_sticker_import_no_subject)
                return
            }
            _preview.value = StickerImportPreview.Ready(cut, "image/png")
        } catch (e: CancellationException) {
            _preview.value = null
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Smart import failed" }
            _preview.value = null
            sendInfo(MR.string.chat_sticker_save_failed)
        }
    }

    /** User tapped "Use" on the preview. */
    fun confirm() {
        val ready = _preview.value as? StickerImportPreview.Ready ?: return
        _preview.value = null
        // Intentionally NOT tracked by [job]: a confirmed save must complete even if the
        // user closes the panel or starts a new import (which cancels [job]).
        // awaitDriveGranted is idempotent, so a concurrent import is safe.
        scope.launch {
            try {
                awaitDriveGranted()
                saveAndReport(ready.bytes, ready.contentType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Confirmed sticker save failed" }
                sendInfo(MR.string.chat_sticker_save_failed)
            }
        }
    }

    /**
     * User tapped "Cancel" on the preview dialog, or started another import — cancel any
     * in-flight work and clear the preview. Note: closing the composer panel does NOT call
     * this; the dialog is hoisted to screen level so an in-flight/ready import deliberately
     * survives a panel or tab change.
     */
    fun dismiss() {
        job?.cancel()
        job = null
        _preview.value = null
    }

    private suspend fun saveAndReport(bytes: ByteArray, contentType: String) {
        val saved = saveSticker(bytes, contentType)
        sendInfo(if (saved != null) MR.string.chat_sticker_saved else MR.string.chat_sticker_save_failed)
    }
}
