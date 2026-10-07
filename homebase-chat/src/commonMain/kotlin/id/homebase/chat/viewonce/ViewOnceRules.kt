package id.homebase.chat.viewonce

import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel

enum class ViewOnceState { Unopened, Opened, Expired, Sent }

object ViewOnceRules {
    const val MAX_LIFESPAN_MS = 30 * 24 * 60 * 60 * 1000L

    fun openedCount(message: MessageUiModel): Int =
        ViewOnceSignal.count(message.reactionPreview, ViewOnceSignal.OPENED_CODE)

    fun screenshotCount(message: MessageUiModel): Int =
        ViewOnceSignal.count(message.reactionPreview, ViewOnceSignal.SCREENSHOT_CODE)

    fun screenshotTaken(message: MessageUiModel, myOdinId: OdinId?): Boolean =
        message.isFromActiveUser(myOdinId) && screenshotCount(message) >= 1

    fun stateOf(message: MessageUiModel, nowMs: Long, myOdinId: OdinId?): ViewOnceState {
        val createdMs = message.created.toEpochMilliseconds()
        val expiredByAge = nowMs - createdMs >= MAX_LIFESPAN_MS
        if (message.isFromActiveUser(myOdinId)) {
            return when {
                openedCount(message) >= 1 -> ViewOnceState.Opened
                expiredByAge -> ViewOnceState.Expired
                else -> ViewOnceState.Sent
            }
        }
        if (message.isDeleted) {
            val updatedMs = (message.modified ?: message.created).toEpochMilliseconds()
            return if (updatedMs - createdMs >= MAX_LIFESPAN_MS) ViewOnceState.Expired else ViewOnceState.Opened
        }
        return when {
            ViewOnceSignal.OPENED_CODE in message.ownReactions -> ViewOnceState.Opened
            expiredByAge -> ViewOnceState.Expired
            else -> ViewOnceState.Unopened
        }
    }
}
