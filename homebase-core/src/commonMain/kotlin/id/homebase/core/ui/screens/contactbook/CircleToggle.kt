package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.OdinApiException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.errorCodeEnum
import id.homebase.chat.services.ChatProtocol

/** Where the toggle is offered, not whether it succeeds: the server decides, and still refuses contacts-app circles. */
fun RedactedCircleDefinition.offersEnableToggle(): Boolean =
    !isLegacySystemCircleId(id) && (appId == ChatProtocol.ChatAppId || isOwnedByContactsApp())

fun Throwable.toCircleToggleError(): ContactBookError =
    when ((this as? OdinApiException)?.problem?.errorCodeEnum()) {
        OdinClientErrorCode.CannotDisableSystemCircle -> ContactBookError.CircleToggleSystemCircle
        OdinClientErrorCode.CircleNotFound -> ContactBookError.CircleToggleNotFound
        else -> if (this is ForbiddenException) ContactBookError.CircleToggleForbidden else ContactBookError.CircleActionFailed
    }
