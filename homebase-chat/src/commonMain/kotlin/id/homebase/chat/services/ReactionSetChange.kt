package id.homebase.chat.services

/**
 * What one vote tap declares for a [scope] of a message: reaction codes to
 * [add] and to [remove]. Sent as a single idempotent outbox row, never as a
 * toggle, so retries and replays converge on the same state.
 */
data class ReactionSetChange(
    val scope: String,
    val add: Set<String>,
    val remove: Set<String>,
)
