@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowInfoMessage
import id.homebase.chat.services.sticker.SavedSticker
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.thumbSizesFrom
import id.homebase.core.image.ImageSize
import id.homebase.resources.MR
import id.homebase.resources.chat_sticker_remove_failed
import id.homebase.resources.chat_sticker_removed
import id.homebase.resources.chat_sticker_save_failed
import id.homebase.resources.chat_sticker_saved
import id.homebase.resources.chat_sticker_send_failed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private const val TAG = "StickerHandler"

/**
 * Handles the sticker-library action arms (send a saved sticker, save a received
 * sticker, remove a sticker), extracted from `ConversationListViewModel.onAction` as a
 * sibling handler class to [AttachmentHandler] / [MediaDownloadHandler].
 *
 * Collaborators are injected as functions (mirroring [StickerCreator]) so the handler is
 * unit-testable without standing up [id.homebase.chat.services.sticker.StickerService] and
 * its drive/database dependencies.
 *
 * Reuses the existing send pipeline: tapping a saved sticker re-stages it as a normal
 * [AttachmentPendingFile.FileImage] with `forceSticker = true` and calls [addMessageWithFiles].
 */
internal class StickerHandler(
    private val scope: CoroutineScope,
    private val messagesUiState: MutableStateFlow<MessageListUiState>,
    private val sendEvent: (ConversationListUiEvent) -> Unit,
    private val addMessageWithFiles: (conversationId: Uuid, content: String, files: List<AttachmentPendingFile>) -> Unit,
    /**
     * Resolve a saved sticker's image to a local temp path so it can be re-staged as a
     * normal image attachment. This is a (possibly slow, cold) drive fetch the first time.
     */
    private val resolveStickerForSend: suspend (SavedSticker) -> String?,
    /** Persist raw sticker bytes to the Stickers drive; returns the new uniqueId or null. */
    private val saveStickerBytes: suspend (bytes: ByteArray, contentType: String, sourceFileId: Uuid?) -> Uuid?,
    /** Enqueue a delete for a saved sticker. */
    private val deleteSticker: suspend (SavedSticker) -> Boolean,
    /** Decrypt a chat message payload's bytes (used when saving a received sticker). */
    private val getPayloadBytes: suspend (fileId: Uuid, key: String, keyHeader: KeyHeader) -> ByteArray?,
    /** Snapshot of the user's saved stickers (for matching a received sticker's source file). */
    private val savedStickers: () -> List<SavedSticker>,
    /**
     * Suspends until the optional Stickers drive is granted (instant when it already is;
     * waits for the extend-permissions dialog on first use). The write paths
     * (save-from-message) gate on this so they never enqueue an upload to an ungranted
     * drive — which the sync engine wouldn't push, silently losing the sticker.
     */
    private val awaitDriveGranted: suspend () -> Unit,
) {

    /**
     * uniqueIds of stickers whose send is currently in flight. A saved-sticker send first
     * does a cold drive fetch ([resolveStickerForSend]) that can take seconds; without this
     * guard every tap during that window launched another full send (the "each press sends
     * a sticker" bug). Accessed only on [scope]'s dispatcher (taps and the resetting
     * `finally` both run there), so a plain set needs no extra synchronisation.
     */
    private val sendingStickerIds = mutableSetOf<Uuid>()

    fun handleSendSavedSticker(action: ConversationListUiAction.SendSavedSticker) {
        val stickerId = action.sticker.uniqueId
        // Ignore repeated taps on the same sticker while its send is in flight. Reset in
        // `finally` so a later, deliberate re-send of the same sticker still works.
        if (!sendingStickerIds.add(stickerId)) return
        scope.launch {
            try {
                val path = resolveStickerForSend(action.sticker)
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
            } finally {
                sendingStickerIds.remove(stickerId)
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
                val bytes = getPayloadBytes(
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
                val saved = saveStickerBytes(
                    bytes,
                    payload.contentType ?: "image/png",
                    // Record which message this sticker came from, so a later sheet open can
                    // detect it's already saved and offer "Remove" instead of "Add".
                    message.fileId,
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

    fun handleRemoveSticker(action: ConversationListUiAction.RemoveSticker) {
        scope.launch {
            try {
                val ok = deleteSticker(action.sticker)
                if (ok) sendEvent(ShowInfoMessage(MR.string.chat_sticker_removed))
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to remove sticker" }
                sendEvent(ShowInfoMessage(MR.string.chat_sticker_remove_failed))
            }
        }
    }

    /**
     * Tapping a sticker bubble opens the sticker-options bottom sheet instead of the
     * fullscreen viewer. We compute whether this received sticker is already in the user's
     * library — by matching any [SavedSticker.sourceFileId] to this message's fileId — so the
     * sheet shows "Add to my stickers" or "Remove from my stickers" accordingly.
     */
    fun handleShowStickerOptions(action: ConversationListUiAction.ShowStickerOptions) {
        val message = action.message
        val isAlreadySaved = findSavedFromMessage(message.fileId) != null
        val stickerPayload = message.payloads?.firstOrNull { it.key == action.payloadKey }
        val payloadIv = stickerPayload?.iv?.let { Base64.decode(it) }
        val stickerImage = HomebaseImageData(
            driveId = chatTargetDrive.alias,
            fileId = message.fileId,
            payloadKey = action.payloadKey,
            previewThumbnail = stickerPayload?.previewThumbnail?.toEmbeddedThumb()
                ?: message.previewThumbnail,
            requestedSize = ImageSize.THUMB_MEDIUM,
            availableThumbSizes = thumbSizesFrom(stickerPayload?.thumbnails),
            lastModified = stickerPayload?.lastModified,
            isEncrypted = true,
            payloadContentType = stickerPayload?.contentType,
            keyHeader = if (payloadIv != null) {
                KeyHeader(iv = payloadIv, aesKey = message.keyHeader.aesKey)
            } else {
                message.keyHeader
            },
        )
        messagesUiState.update {
            it.copy(
                stickerOptionsSheet = StickerOptionsSheetState(
                    messageId = message.id,
                    payloadKey = action.payloadKey,
                    sourceFileId = message.fileId,
                    isAlreadySaved = isAlreadySaved,
                    stickerImage = stickerImage,
                )
            )
        }
    }

    fun handleDismissStickerOptions() {
        messagesUiState.update { it.copy(stickerOptionsSheet = null) }
    }

    /**
     * "Remove from my stickers" arm of the sheet: deletes the saved copy that was created from
     * this message (matched by [SavedSticker.sourceFileId]) WITHOUT touching the chat message
     * itself. No-op (with a failure toast) if no matching saved sticker is found.
     */
    fun handleRemoveStickerFromMessage(action: ConversationListUiAction.RemoveStickerFromMessage) {
        scope.launch {
            try {
                val saved = findSavedFromMessage(action.sourceFileId)
                if (saved == null) {
                    sendEvent(ShowInfoMessage(MR.string.chat_sticker_remove_failed))
                    return@launch
                }
                val ok = deleteSticker(saved)
                if (ok) sendEvent(ShowInfoMessage(MR.string.chat_sticker_removed))
                else sendEvent(ShowInfoMessage(MR.string.chat_sticker_remove_failed))
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to remove sticker from message" }
                sendEvent(ShowInfoMessage(MR.string.chat_sticker_remove_failed))
            }
        }
    }

    private fun findSavedFromMessage(sourceFileId: Uuid): SavedSticker? =
        savedStickers().firstOrNull { it.sourceFileId == sourceFileId }
}
