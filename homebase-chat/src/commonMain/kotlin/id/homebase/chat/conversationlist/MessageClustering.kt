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

        val sameAsPrev = prev != null &&
            item.message.sender == prev.message.sender &&
            (item.message.userDate - prev.message.userDate) < CLUSTER_TIME_WINDOW

        val sameAsNext = next != null &&
            item.message.sender == next.message.sender &&
            (next.message.userDate - item.message.userDate) < CLUSTER_TIME_WINDOW

        item.copy(clusterPosition = clusterPosition(sameAsPrev, sameAsNext))
    }
}

internal fun clusterPosition(sameAsPrev: Boolean, sameAsNext: Boolean): MessageClusterPosition = when {
    sameAsPrev && sameAsNext -> MessageClusterPosition.MIDDLE
    sameAsPrev -> MessageClusterPosition.END
    sameAsNext -> MessageClusterPosition.START
    else -> MessageClusterPosition.ALONE
}

/**
 * Groups each pending placeholder in [items] (oldest first) with its neighbours, as its real
 * message will be once it lands, so the takeover doesn't regroup and re-space the rows around it.
 * A placeholder is always [ownerOdinId]'s. A date [MessageListContentModel.Section] ends a group.
 */
internal fun clusterPendingInto(items: List<Any>, ownerOdinId: OdinId?): List<Any> {
    if (ownerOdinId == null || items.none { it is PendingOutgoingMessage }) return items
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
    fun senderAndTime(item: Any): Pair<OdinId?, Instant> = when (item) {
        is MessageListContentModel.Message -> item.message.sender to item.message.userDate
        is PendingOutgoingMessage -> ownerOdinId to item.sentAt
        else -> error("not a message: $item")
    }
    fun grouped(earlier: Int?, later: Int?): Boolean {
        if (earlier == null || later == null) return false
        val (a, aTime) = senderAndTime(items[earlier])
        val (b, bTime) = senderAndTime(items[later])
        return a == b && (bTime - aTime) < CLUSTER_TIME_WINDOW
    }
    val result = items.toMutableList()
    items.forEachIndexed { index, item ->
        if (item !is PendingOutgoingMessage) return@forEachIndexed
        val before = neighbour(index, -1)
        val after = neighbour(index, 1)
        result[index] = item.copy(clusterPosition = clusterPosition(grouped(before, index), grouped(index, after)))
        // A neighbour that's a real message joins the placeholder's group now, as it will when the real one lands.
        (before?.let { result[it] } as? MessageListContentModel.Message)?.takeIf { grouped(before, index) }?.let {
            result[before] = it.copy(clusterPosition = clusterPosition(it.clusterPosition.groupedBefore(), true))
        }
        (after?.let { result[it] } as? MessageListContentModel.Message)?.takeIf { grouped(index, after) }?.let {
            result[after] = it.copy(clusterPosition = clusterPosition(true, it.clusterPosition.groupedAfter()))
        }
    }
    return result
}

private fun MessageClusterPosition.groupedBefore() = this == MessageClusterPosition.MIDDLE || this == MessageClusterPosition.END
private fun MessageClusterPosition.groupedAfter() = this == MessageClusterPosition.START || this == MessageClusterPosition.MIDDLE
