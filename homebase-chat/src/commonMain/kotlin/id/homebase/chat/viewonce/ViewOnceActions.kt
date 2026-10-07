package id.homebase.chat.viewonce

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ReactionSetChange
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

    // The viewer's screenshot and emoji rows chain in the order they were queued, and `_vo` waits
    // for the last of them: the outbox is not FIFO, so a delete that drains first loses them.
    private val signalMutex = Mutex()
    private val shotSent = mutableSetOf<Uuid>()
    private val reacted = mutableSetOf<Uuid>()
    private val lastSignalRow = mutableMapOf<Uuid, Uuid>()

    private suspend fun enqueueSignal(
        conversationId: Uuid,
        messageId: Uuid,
        change: ReactionSetChange,
    ): MutationOutcome {
        val outcome = actionService.setReactions(
            conversationId, messageId, change, runAfter = lastSignalRow[messageId],
        )
        if (outcome == MutationOutcome.Queued) {
            lastSignalRow[messageId] = ChatMessageActionService.reactionSetRowKey(messageId, change.scope)
        }
        return outcome
    }

    /** At most once per viewer session; a failed enqueue frees the next screenshot to try again. */
    suspend fun onScreenshot(conversationId: Uuid, messageId: Uuid): Unit = signalMutex.withLock {
        if (messageId in shotSent) return@withLock
        val outcome = try {
            enqueueSignal(conversationId, messageId, ViewOnceSignal.screenshotChange())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, e) { "screenshot signal failed msg=$messageId" }
            return@withLock
        }
        Logger.i(TAG) { "screenshot msg=$messageId signal=$outcome" }
        if (outcome == MutationOutcome.Queued) shotSent.add(messageId)
    }

    /** One emoji per viewing, add-only: a second pick would replace a still-pending row and lose the first. */
    suspend fun onReact(conversationId: Uuid, messageId: Uuid, emoji: String): Unit = signalMutex.withLock {
        if (messageId in reacted) return@withLock
        val outcome = try {
            enqueueSignal(conversationId, messageId, ViewOnceSignal.reactionChange(emoji))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, e) { "viewer reaction failed msg=$messageId" }
            return@withLock
        }
        Logger.i(TAG) { "viewer reaction msg=$messageId outcome=$outcome" }
        if (outcome == MutationOutcome.Queued) reacted.add(messageId)
    }

    private suspend fun claim(messageId: Uuid): Boolean = claimMutex.withLock { claimed.add(messageId) }

    suspend fun isConsumed(messageId: Uuid): Boolean = claimMutex.withLock { messageId in claimed }

    private suspend fun release(messageId: Uuid) = claimMutex.withLock { claimed.remove(messageId) }

    private suspend fun releasingOnFailure(messageId: Uuid, failure: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            release(messageId)
            throw e
        } catch (e: Exception) {
            release(messageId)
            Logger.e(TAG, e) { "$failure msg=$messageId" }
        }
    }

    /** The viewer's screenshot and emoji rows (if any), then `_vo`, then the soft delete, each waiting for the one before. */
    suspend fun onViewerClosed(conversationId: Uuid, messageId: Uuid) {
        if (!claim(messageId)) return
        releasingOnFailure(messageId, "viewer close failed") {
            val outcome = signalMutex.withLock {
                reacted.remove(messageId)
                enqueueSignal(conversationId, messageId, ViewOnceSignal.openedChange())
                    .also { if (it == MutationOutcome.Queued) lastSignalRow.remove(messageId) }
            }
            Logger.i(TAG) { "viewer closed msg=$messageId opened-signal=$outcome" }
            actionService.deleteMessage(
                messageId = messageId,
                deleteForEveryone = false,
                runAfter = ChatMessageActionService.reactionSetRowKey(messageId, ViewOnceSignal.OPENED_SCOPE),
            )
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
            releasingOnFailure(message.id, "sweep delete failed") {
                if (actionService.isDeletedLocally(message.id)) return@releasingOnFailure
                actionService.deleteMessage(
                    messageId = message.id,
                    deleteForEveryone = false,
                    runAfter = if (opened) {
                        ChatMessageActionService.reactionSetRowKey(message.id, ViewOnceSignal.OPENED_SCOPE)
                    } else null,
                )
            }
        }
    }

    private companion object {
        const val TAG = "ViewOnceActions"
    }
}
