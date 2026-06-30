package id.homebase.core.ui.screens.contactbook.add

import androidx.compose.runtime.Immutable
import id.homebase.core.connections.RecipientResolution
import id.homebase.core.ui.screens.contactbook.ContactDraft
import io.github.vinceglb.filekit.PlatformFile

/** Whether the Add Contact flow leads with a Homebase ID lookup or a plain manual form. */
enum class AddContactMode { BY_IDENTITY, MANUAL }

@Immutable
data class AddContactUiState(
    val mode: AddContactMode = AddContactMode.BY_IDENTITY,
    /** Live lookup status for the entered Homebase ID (BY_IDENTITY mode). */
    val resolution: RecipientResolution = RecipientResolution.Idle,
    /** True when the resolved identity is already a connection — suppresses the request offer. */
    val alreadyConnected: Boolean = false,
    val draft: ContactDraft = ContactDraft(),
    val photo: PlatformFile? = null,
    val isSaving: Boolean = false,
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
    data object Back : AddContactEvent
}
