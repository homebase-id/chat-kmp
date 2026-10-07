package id.homebase.chat.viewonce

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.outbox.MutationOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** The recipient's side of the view-once lifecycle: consume on close, recover, expire. */
class ViewOnceActions(
    private val actionService: ChatMessageActionService,
    private val credentialsManager: CredentialsManager,
) {
    // Closing, the sweep and a re-emitted page can all reach the same message at once.
    private val claimMutex = Mutex()
    private val claimed = mutableSetOf<Uuid>()

    private val shotMutex = Mutex()
    private val shotSent = mutableSetOf<Uuid>()

    /** At most once per viewer session; a failed enqueue frees the next screenshot to try again. */
    suspend fun onScreenshot(conversationId: Uuid, messageId: Uuid) {
        if (!shotMutex.withLock { shotSent.add(messageId) }) return
        suspend fun free() = shotMutex.withLock { shotSent.remove(messageId) }
        try {
            val outcome = actionService.setReactions(conversationId, messageId, ViewOnceSignal.screenshotChange())
            Logger.i(TAG) { "screenshot msg=$messageId signal=$outcome" }
            if (outcome != MutationOutcome.Queued) free()
        } catch (e: CancellationException) {
            free()
            throw e
        } catch (e: Exception) {
            free()
            Logger.e(TAG, e) { "screenshot signal failed msg=$messageId" }
        }
    }

    private suspend fun claim(messageId: Uuid): Boolean = claimMutex.withLock { claimed.add(messageId) }

    suspend fun isConsumed(messageId: Uuid): Boolean = claimMutex.withLock { messageId in claimed }

    private suspend fun release(messageId: Uuid) = claimMutex.withLock { claimed.remove(messageId) }

    /**
     * `_vo`, then the soft delete: the delete waits for the reaction row, because the outbox is not
     * FIFO and a reaction sent after the delete is lost.
     */
    suspend fun onViewerClosed(conversationId: Uuid, messageId: Uuid) {
        if (!claim(messageId)) return
        try {
            val outcome = actionService.setReactions(conversationId, messageId, ViewOnceSignal.openedChange())
            Logger.i(TAG) { "viewer closed msg=$messageId opened-signal=$outcome" }
            actionService.deleteMessage(
                messageId = messageId,
                deleteForEveryone = false,
                runAfter = ChatMessageActionService.reactionSetRowKey(messageId, ViewOnceSignal.OPENED_SCOPE),
            )
        } catch (e: CancellationException) {
            release(messageId)
            throw e
        } catch (e: Exception) {
            release(messageId)
            Logger.e(TAG, e) { "viewer close failed msg=$messageId" }
        }
    }

    /**
     * Re-runnable: an opened copy whose delete never got queued gets it now, and an unopened copy
     * past [ViewOnceRules.MAX_LIFESPAN_MS] is deleted without an `_vo`. Own messages are never touched.
     * A delete already queued has already tombstoned the local row, so it is not queued twice.
     */
    suspend fun sweep(messages: List<MessageUiModel>, nowMs: Long) {
        if (messages.none { it.messageContent is MessageContent.ViewOnce && !it.isDeleted }) return
        val me = credentialsManager.requireActiveDomain()
        for (message in messages) {
            if (message.messageContent !is MessageContent.ViewOnce) continue
            if (message.isDeleted || message.isPendingSend || message.isFromActiveUser(me)) continue
            val opened = ViewOnceSignal.OPENED_CODE in message.ownReactions
            val expired = nowMs - message.created.toEpochMilliseconds() >= ViewOnceRules.MAX_LIFESPAN_MS
            if (!opened && !expired) continue
            if (!claim(message.id)) continue
            try {
                if (actionService.isDeletedLocally(message.id)) continue
                actionService.deleteMessage(
                    messageId = message.id,
                    deleteForEveryone = false,
                    runAfter = if (opened) {
                        ChatMessageActionService.reactionSetRowKey(message.id, ViewOnceSignal.OPENED_SCOPE)
                    } else null,
                )
            } catch (e: CancellationException) {
                release(message.id)
                throw e
            } catch (e: Exception) {
                release(message.id)
                Logger.e(TAG, e) { "sweep delete failed msg=${message.id}" }
            }
        }
    }

    private companion object {
        const val TAG = "ViewOnceActions"
    }
}
