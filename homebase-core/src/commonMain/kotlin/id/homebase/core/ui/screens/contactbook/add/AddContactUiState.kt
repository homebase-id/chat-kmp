@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.add

import androidx.compose.runtime.Immutable
import id.homebase.core.connections.RecipientResolution
import id.homebase.core.ui.screens.contactbook.ContactDraft
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Whether the Add Contact flow leads with a Homebase ID lookup or a plain manual form. */
enum class AddContactMode { BY_IDENTITY, MANUAL }

/**
 * The signed-in user's relationship to a resolved identity — drives which connection action the
 * Add Contact card offers. Derived live from the connection map plus the pending incoming/outgoing
 * request lists.
 */
enum class IdentityRelation {
    /** Not connected and no pending request either way — offer to send one. */
    NONE,
    /** We've sent them a request that's still pending — offer to cancel it. */
    OUTGOING_PENDING,
    /** They've sent us a request — offer to accept or reject. */
    INCOMING_PENDING,
    /** Already connected. */
    CONNECTED,
    /** We've blocked them. */
    BLOCKED,
}

@Immutable
data class AddContactUiState(
    val mode: AddContactMode = AddContactMode.BY_IDENTITY,
    /** Live lookup status for the entered Homebase ID (BY_IDENTITY mode). */
    val resolution: RecipientResolution = RecipientResolution.Idle,
    /** Relationship to the resolved identity — selects the applicable connection action. */
    val relation: IdentityRelation = IdentityRelation.NONE,
    /** True when the resolved identity is already saved in the contact book. */
    val alreadySaved: Boolean = false,
    /**
     * All user-defined circles the signed-in user could add a contact to (system circles
     * excluded), A–Z. Feeds the "Add to circles" picker offered next to Accept on an incoming
     * request — the selection rides the accept call atomically.
     */
    val assignableCircles: List<ContactCircleUi> = emptyList(),
    val draft: ContactDraft = ContactDraft(),
    val photo: PlatformFile? = null,
    val isSaving: Boolean = false,
    /** An accept/reject/cancel request action is in flight — disables the action buttons. */
    val actionInProgress: Boolean = false,
) {
    val canSave: Boolean get() = draft.isSavable && !isSaving
}

sealed interface AddContactAction {
    /** The Homebase ID field changed — re-runs the debounced identity lookup. */
    data class OdinIdChanged(val value: String) : AddContactAction
    data class DraftChanged(val draft: ContactDraft) : AddContactAction
    data class PhotoPicked(val photo: PlatformFile?) : AddContactAction
    data object SwitchToManual : AddContactAction
    data object SwitchToByIdentity : AddContactAction
    data object SaveClicked : AddContactAction
    /** Open (or reuse) the 1:1 conversation with the resolved, already-connected identity. */
    data object MessageClicked : AddContactAction
    /**
     * Accept the incoming request from the resolved identity, placing them into [circleIds]
     * (32-char N-format ids from [AddContactUiState.assignableCircles]) as part of the same
     * call. Empty = accept without adding to any circle.
     */
    data class AcceptRequestClicked(val circleIds: List<String>) : AddContactAction
    /** Reject the incoming request from the resolved identity. */
    data object RejectRequestClicked : AddContactAction
    /** Cancel the outgoing request to the resolved identity. */
    data object CancelRequestClicked : AddContactAction
    data object BackClicked : AddContactAction
}

sealed interface AddContactEvent {
    /** Contact saved — pop back to the contact list. */
    data object Saved : AddContactEvent
    /** Saved, but the avatar upload failed. */
    data object PhotoFailed : AddContactEvent
    /** 403 — the app lacks the manage-contacts permission. */
    data object Forbidden : AddContactEvent
    /** Any other save failure. */
    data object Error : AddContactEvent
    /** An incoming request was accepted — stay on screen, show a confirmation. */
    data object RequestAccepted : AddContactEvent
    /** An incoming request was rejected. */
    data object RequestRejected : AddContactEvent
    /** An outgoing request was cancelled. */
    data object RequestCancelled : AddContactEvent
    /** An accept/reject/cancel action failed. */
    data object RequestActionFailed : AddContactEvent
    /** Accept failed because the sender already withdrew the request (server-confirmed gone). */
    data object RequestWithdrawn : AddContactEvent
    /** Open the 1:1 conversation with an already-connected identity. */
    data class OpenConversation(val conversationId: Uuid) : AddContactEvent
    data object Back : AddContactEvent
}
