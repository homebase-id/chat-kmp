package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.errorCodeEnum

/**
 * Why a single contact's `circles/add` call failed, shaped so the UI layer (not this ViewModel-
 * agnostic classification) resolves the actual display string via `stringResource` — a
 * ViewModel can't call that directly. [Raw] carries real, server-provided text (a 400
 * ClientException's message already is the server's title); the two 403 cases need distinct
 * copy because only one of them is user-actionable.
 */
sealed interface CircleAddFailureReason {
    /** A 400 (or any other exception whose message is already a real, useful explanation). */
    data class Raw(val message: String) : CircleAddFailureReason

    /** 403 with errorCode 4173 (CannotSourceDriveStorageKeyForGrant) — this app genuinely can't
     *  be granted this circle because it includes a drive the app doesn't have access to. Real,
     *  user-facing, not transient — don't offer a retry. */
    data object DriveAccessDenied : CircleAddFailureReason

    /** Any other 403 (missing ManageCircleMembership, contact not connected, etc) — the server
     *  gives no code to distinguish these from each other, and per the API contract they're
     *  client-side/config bugs, not user-facing outcomes. Log the real reason; show a generic
     *  message rather than surfacing (possibly cryptic) internal detail as if it explained
     *  anything actionable. */
    data object OpaqueForbidden : CircleAddFailureReason
}

/** Classifies a `connectionService.addToCircle` failure into a [CircleAddFailureReason]. */
fun Throwable.toCircleAddFailureReason(): CircleAddFailureReason = when {
    this is ForbiddenException && problem?.errorCodeEnum() == OdinClientErrorCode.CannotSourceDriveStorageKeyForGrant ->
        CircleAddFailureReason.DriveAccessDenied
    this is ForbiddenException -> CircleAddFailureReason.OpaqueForbidden
    else -> CircleAddFailureReason.Raw(message ?: "Failed")
}
