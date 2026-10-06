@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.OdinApiException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.errorCodeEnum
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.contact.ConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Why this app can't enable/disable the circle, or null when it may try: the server still decides, and still refuses contacts-app circles. */
fun RedactedCircleDefinition?.toggleBlockedReason(): ContactBookError? = when {
    this == null -> ContactBookError.CircleToggleNotFound
    isLegacySystemCircleId(id) -> ContactBookError.CircleToggleSystemCircle
    appId == ChatProtocol.ChatAppId || isOwnedByContactsApp() -> null
    else -> ContactBookError.CircleToggleForbidden
}

fun Throwable.toCircleToggleError(): ContactBookError =
    when ((this as? OdinApiException)?.problem?.errorCodeEnum()) {
        OdinClientErrorCode.CannotDisableSystemCircle -> ContactBookError.CircleToggleSystemCircle
        OdinClientErrorCode.CircleNotFound -> ContactBookError.CircleToggleNotFound
        else -> if (this is ForbiddenException) ContactBookError.CircleToggleForbidden else ContactBookError.CircleActionFailed
    }

/** The new `disabled` value arrives through the owner's circles collector, not from here. */
fun CoroutineScope.launchCircleToggle(
    connectionService: ConnectionService,
    circleId: String,
    enabled: Boolean,
    update: ((CircleMembersUi) -> CircleMembersUi) -> Unit,
) {
    update { it.copy(togglingEnabled = true, toggleError = null) }
    launch {
        try {
            connectionService.setCircleEnabled(Uuid.parseHex(circleId), enabled)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, "CircleToggle") { "setCircleEnabled($enabled) failed for $circleId" }
            update { it.copy(toggleError = e.toCircleToggleError()) }
        } finally {
            update { it.copy(togglingEnabled = false) }
        }
    }
}

/** Re-reads the open sheet's circle, so a toggle made anywhere lands on it. */
fun CircleMembersUi.withCircle(def: RedactedCircleDefinition?): CircleMembersUi =
    copy(disabled = def?.disabled ?: disabled, toggleBlockedReason = def.toggleBlockedReason())
