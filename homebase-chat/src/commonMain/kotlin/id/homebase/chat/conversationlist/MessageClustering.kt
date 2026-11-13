package id.homebase.chat.conversationlist

import id.homebase.api.common.OdinId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val CLUSTER_TIME_WINDOW = 3.minutes

/**
 * Assigns a [MessageClusterPosition] to each message based on whether consecutive
 * messages share the same sender and fall within a 3-minute window (matching Signal).
 *
 * Messages within a cluster get tighter spacing (1dp) and collapsed corner radii,
 * while cluster boundaries get normal spacing (6dp) and full rounded corners.
 */
internal fun computeClusterPositions(
    messages: List<MessageListContentModel.Message>,
): List<MessageListContentModel.Message> {
    if (messages.isEmpty()) return messages
    if (messages.size == 1) return listOf(messages[0].copy(clusterPosition = MessageClusterPosition.ALONE))

    return messages.mapIndexed { index, item ->
        val prev = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        val sameAsPrev = prev != null && inCluster(prev.message.sender, prev.message.userDate, item.message.sender, item.message.userDate)
        val sameAsNext = next != null && inCluster(item.message.sender, item.message.userDate, next.message.sender, next.message.userDate)
        item.copy(clusterPosition = clusterPosition(sameAsPrev, sameAsNext))
    }
}

internal fun clusterPosition(sameAsPrev: Boolean, sameAsNext: Boolean): MessageClusterPosition = when {
    sameAsPrev && sameAsNext -> MessageClusterPosition.MIDDLE
    sameAsPrev -> MessageClusterPosition.END
    sameAsNext -> MessageClusterPosition.START
    else -> MessageClusterPosition.ALONE
}

private fun inCluster(earlierSender: OdinId?, earlierTime: Instant, laterSender: OdinId?, laterTime: Instant) =
    earlierSender == laterSender && (laterTime - earlierTime) < CLUSTER_TIME_WINDOW

// Placeholders cluster as their real message will, so the hand-off doesn't re-space the rows around them.
internal fun clusterPendingInto(items: List<Any>, ownerOdinId: OdinId?): List<Any> {
    if (ownerOdinId == null) return items
    // Clustering skips system and separator rows; only a date section ends a group.
    fun neighbour(from: Int, step: Int): Int? {
        var i = from + step
        while (i in items.indices) {
            when (items[i]) {
                is MessageListContentModel.Message, is PendingOutgoingMessage -> return i
                is MessageListContentModel.Section -> return null
            }
            i += step
        }
        return null
    }
    fun senderAndTime(i: Int): Pair<OdinId?, Instant> = when (val item = items[i]) {
        is MessageListContentModel.Message -> item.message.sender to item.message.userDate
        else -> ownerOdinId to (item as PendingOutgoingMessage).sentAt
    }
    fun grouped(earlier: Int?, later: Int?): Boolean {
        if (earlier == null || later == null) return false
        val (a, aTime) = senderAndTime(earlier)
        val (b, bTime) = senderAndTime(later)
        return inCluster(a, aTime, b, bTime)
    }
    return items.mapIndexed { i, item ->
        if (item !is MessageListContentModel.Message && item !is PendingOutgoingMessage) return@mapIndexed item
        val position = clusterPosition(grouped(neighbour(i, -1), i), grouped(i, neighbour(i, 1)))
        when (item) {
            is MessageListContentModel.Message -> if (position == item.clusterPosition) item else item.copy(clusterPosition = position)
            else -> (item as PendingOutgoingMessage).copy(clusterPosition = position)
        }
    }
}
