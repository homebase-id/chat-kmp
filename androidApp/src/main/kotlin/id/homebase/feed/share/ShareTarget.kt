package id.homebase.feed.share

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Where the user chose to send the shared content. The picker emits one of
 * these; [ShareReceiverActivity] branches on it exactly once so the capture →
 * convert → caption steps stay shared and only the terminal dispatch diverges.
 *
 * - [Conversations] runs the chat send path ([ChatMessageSenderService]).
 * - [NewMoment] seeds the moments composer draft and hands off to
 *   `Route.MomentCompose`, reusing the same media-upload pipeline downstream.
 */
@OptIn(ExperimentalUuidApi::class)
sealed interface ShareTarget {
    data class Conversations(val ids: Set<Uuid>) : ShareTarget
    data object NewMoment : ShareTarget
}
