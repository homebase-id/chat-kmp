package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.OdinClientErrorCode
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Why an incoming-request action failed, as far as any request surface needs to care.
 *
 * [Withdrawn] and [Forbidden] are terminal: the sender's request is gone, or this app lacks
 * manage-connections permission — retrying fixes neither.
 */
enum class ConnectionRequestFailure {
    Withdrawn,
    Forbidden,
    Transient,
}

val ConnectionRequestFailure.isTerminal: Boolean
    get() = this != ConnectionRequestFailure.Transient

/**
 * A withdrawn request means the accept raced a since-completed cancel-outgoing on the sender's
 * side; `ConnectionRequestService.acceptIncomingRequest` has already dropped the stale local copy
 * before rethrowing, so the caller only needs the message.
 */
fun Throwable.connectionRequestFailure(): ConnectionRequestFailure = when {
    this is ForbiddenException -> ConnectionRequestFailure.Forbidden
    this is ClientException && errorCode == OdinClientErrorCode.IncomingRequestNotFound ->
        ConnectionRequestFailure.Withdrawn

    else -> ConnectionRequestFailure.Transient
}

/** Circle ids arrive as 32-char N-format strings; a malformed one is dropped, not fatal. */
@OptIn(ExperimentalUuidApi::class)
fun Iterable<String>.toCircleUuids(): List<Uuid> =
    mapNotNull { runCatching { Uuid.parseHex(it) }.getOrNull() }
