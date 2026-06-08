@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.image.ImageUtils
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowInfoMessage
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.sticker.StickerService
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.resources.MR
import id.homebase.resources.chat_sticker_import_not_transparent
import id.homebase.resources.chat_sticker_remove_failed
import id.homebase.resources.chat_sticker_removed
import id.homebase.resources.chat_sticker_save_failed
import id.homebase.resources.chat_sticker_saved
import id.homebase.resources.chat_sticker_send_failed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private const val TAG = "StickerHandler"

/**
 * Handles the sticker-library action arms (send a saved sticker, save a received
 * sticker, import a transparent image, remove a sticker), extracted from
 * `ConversationListViewModel.onAction` in the same handler-class style as
 * [AttachmentHandler] / [MediaDownloadHandler].
 *
 * Reuses the existing send pipeline: tapping a saved sticker re-stages it as a
 * normal [AttachmentPendingFile.FileImage] with `forceSticker = true` and calls
 * [addMessageWithFiles] — no new send code. Saving a received sticker decrypts the
 * payload via the same `getPayloadBytes` path [MediaDownloadHandler] uses.
 */
internal class StickerHandler(
    private val scope: CoroutineScope,
    private val messagesUiState: MutableStateFlow<MessageListUiState>,
    private val stickerService: StickerService,
    private val chatMessageActionService: ChatMessageActionService,
    private val sendEvent: (ConversationListUiEvent) -> Unit,
    private val addMessageWithFiles: (conversationId: Uuid, content: String, files: List<AttachmentPendingFile>) -> Unit,
    /**
     * Suspends until the optional Stickers drive is granted (instant when it already is;
     * waits for the extend-permissions dialog on first use). The write paths
     * (import / save-from-message) gate on this so they never enqueue an upload to an
     * ungranted drive — which the sync engine wouldn't push, silently losing the sticker.
     */
    private val awaitDriveGranted: suspend () -> Unit,
) {

    fun handleSendSavedSticker(action: ConversationListUiAction.SendSavedSticker) {
        scope.launch {
            try {
                val path = stickerService.resolveForSend(action.sticker)
                if (path == null) {
                    sendEvent(ShowInfoMessage(MR.string.chat_sticker_save_failed))
                    return@launch
                }
                val pending = AttachmentPendingFile.FileImage(
                    id = Uuid.generateV7(),
                    file = platformFileFromPath(path),
                    forceSticker = true,
                )
                addMessageWithFiles(action.conversationId, "", listOf(pending))
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to send saved sticker" }
                sendEvent(ShowInfoMessage(MR.string.chat_sticker_send_failed))
            }
        }
    }

    fun handleSaveStickerFromMessage(action: ConversationListUiAction.SaveStickerFromMessage) {
        scope.launch {
            try {
                val message = messagesUiState.value.messages
                    .filterIsInstance<MessageListContentModel.Message>()
                    .map { it.message }
                    .find { it.id == action.messageId } ?: return@launch
                val payload = message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                val iv = payload.iv ?: run {
                    sendEvent(ShowInfoMessage(MR.string.chat_sticker_save_failed))
                    return@launch
                }
                val bytes = chatMessageActionService.getPayloadBytes(
                    message.fileId,
                    action.payloadKey,
                    KeyHeader(Base64.decode(iv), message.keyHeader.aesKey),
                )
                if (bytes == null) {
                    sendEvent(ShowInfoMessage(MR.string.chat_sticker_save_failed))
                    return@launch
                }
                // Don't enqueue the upload until the Stickers drive is granted, or the
                // sync engine would drop it (ungranted drives aren't pushed).
                awaitDriveGranted()
                val saved = stickerService.saveSticker(
                    bytes = bytes,
                    contentType = payload.contentType ?: "image/png",
                    scope = scope,
                )
                sendEvent(
                    ShowInfoMessage(
                        if (saved != null) MR.string.chat_sticker_saved
                        else MR.string.chat_sticker_save_failed
                    )
                )
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to save sticker from message" }
                sendEvent(ShowInfoMessage(MR.string.chat_sticker_save_failed))
            }
        }
    }

    fun handleImportSticker(action: ConversationListUiAction.ImportSticker) {
        scope.launch {
            try {
                // Alpha-gate: a sticker must be a transparent cut-out. A fully-opaque
                // image (ordinary photo / opaque PNG / JPEG) would render with a solid
                // rectangle on the chat wallpaper, so reject it with a clear message.
                if (!ImageUtils.hasNonOpaquePixels(action.bytes)) {
                    sendEvent(ShowInfoMessage(MR.string.chat_sticker_import_not_transparent))
                    return@launch
                }
                // Don't enqueue the upload until the Stickers drive is granted, or the
                // sync engine would drop it (ungranted drives aren't pushed).
                awaitDriveGranted()
                val saved = stickerService.saveSticker(
                    bytes = action.bytes,
                    contentType = action.contentType,
                    scope = scope,
                )
                sendEvent(
                    ShowInfoMessage(
                        if (saved != null) MR.string.chat_sticker_saved
                        else MR.string.chat_sticker_save_failed
                    )
                )
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to import sticker" }
                sendEvent(ShowInfoMessage(MR.string.chat_sticker_save_failed))
            }
        }
    }

    fun handleRemoveSticker(action: ConversationListUiAction.RemoveSticker) {
        scope.launch {
            try {
                val ok = stickerService.deleteSticker(action.sticker)
                if (ok) sendEvent(ShowInfoMessage(MR.string.chat_sticker_removed))
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to remove sticker" }
                sendEvent(ShowInfoMessage(MR.string.chat_sticker_remove_failed))
            }
        }
    }
}
