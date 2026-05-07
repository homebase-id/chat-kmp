package id.homebase.chat.conversationlist

import kotlin.time.Duration.Companion.minutes

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

        val position = when {
            sameAsPrev && sameAsNext -> MessageClusterPosition.MIDDLE
            sameAsPrev -> MessageClusterPosition.END
            sameAsNext -> MessageClusterPosition.START
            else -> MessageClusterPosition.ALONE
        }

        item.copy(clusterPosition = position)
    }
}
