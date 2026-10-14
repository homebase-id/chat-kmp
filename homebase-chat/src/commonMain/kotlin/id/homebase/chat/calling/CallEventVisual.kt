package id.homebase.chat.calling

enum class CallLogDirection { Outgoing, Incoming }

enum class CallEventIcon { Missed, NoAnswerOutgoing, Cancelled, Declined, Busy, Failed, Outgoing, Incoming }

enum class CallEventTitle { Outgoing, Incoming, NoAnswer, Missed, Cancelled, Failed, Declined, Busy }

data class CallEventVisual(
    val direction: CallLogDirection,
    val missed: Boolean,
    val icon: CallEventIcon,
    val title: CallEventTitle,
)

/** The one classification shared by the chat bubble and the Calls tab; [isOwn] means this user placed the call. */
fun callEventVisual(descriptor: CallEventDescriptor, isOwn: Boolean): CallEventVisual {
    val outcome = descriptor.outcome
    val missed = !isOwn && (outcome == CallOutcome.NoAnswer || outcome == CallOutcome.Cancelled)
    val icon = when {
        missed -> CallEventIcon.Missed
        outcome == CallOutcome.NoAnswer -> CallEventIcon.NoAnswerOutgoing
        outcome == CallOutcome.Cancelled -> CallEventIcon.Cancelled
        outcome == CallOutcome.Declined -> CallEventIcon.Declined
        outcome == CallOutcome.Busy -> CallEventIcon.Busy
        outcome == CallOutcome.Failed -> CallEventIcon.Failed
        isOwn -> CallEventIcon.Outgoing
        else -> CallEventIcon.Incoming
    }
    val title = when (outcome) {
        CallOutcome.Completed -> if (isOwn) CallEventTitle.Outgoing else CallEventTitle.Incoming
        CallOutcome.NoAnswer -> if (isOwn) CallEventTitle.NoAnswer else CallEventTitle.Missed
        CallOutcome.Cancelled -> if (isOwn) CallEventTitle.Cancelled else CallEventTitle.Missed
        CallOutcome.Failed -> CallEventTitle.Failed
        CallOutcome.Declined -> CallEventTitle.Declined
        CallOutcome.Busy -> CallEventTitle.Busy
    }
    return CallEventVisual(
        direction = if (isOwn) CallLogDirection.Outgoing else CallLogDirection.Incoming,
        missed = missed,
        icon = icon,
        title = title,
    )
}
