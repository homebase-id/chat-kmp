package id.homebase.chat.services.requests

import id.homebase.api.client.connections.ConnectionStatus

/** Which way a pending connection request points relative to the signed-in identity. */
enum class RequestDirection {
    /** Someone wants to connect with me (I can Accept / Reject). */
    INCOMING,
    /** I asked to connect with them (I can Cancel). */
    OUTGOING,
}

/**
 * The one rule for whether an identity has a live request, and which way. Every surface that
 * shows or acts on a request asks this, so they can't disagree.
 *
 * Connected or Blocked has none: a leftover request is stale, and blocking is the answer. Incoming
 * beats outgoing, because accepting it connects us and settles both.
 */
fun pendingRequestDirection(
    status: ConnectionStatus?,
    hasIncoming: Boolean,
    hasOutgoing: Boolean,
): RequestDirection? = when {
    status == ConnectionStatus.Connected || status == ConnectionStatus.Blocked -> null
    hasIncoming -> RequestDirection.INCOMING
    hasOutgoing -> RequestDirection.OUTGOING
    else -> null
}
