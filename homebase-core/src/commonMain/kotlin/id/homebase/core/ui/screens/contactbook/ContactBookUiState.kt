package id.homebase.core.ui.screens.contactbook

import androidx.compose.runtime.Immutable
import id.homebase.core.contactbook.DeviceContact
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import io.github.vinceglb.filekit.PlatformFile

/** Source filter for the list. */
enum class ContactFilter { ALL, HOMEBASE, IMPORTED }

/** A full-screen-ish overlay shown over the contact list. */
sealed interface ContactBookOverlay {
    /** Read-only detail for one contact. */
    data class Detail(val entry: ContactBookEntry) : ContactBookOverlay

    /** Create ([entry] == null) or edit an existing contact. */
    data class Edit(val entry: ContactBookEntry?) : ContactBookOverlay
}

/** Editable form fields for create/edit. */
@Immutable
data class ContactDraft(
    val givenName: String = "",
    val surname: String = "",
    val odinId: String = "",
    val phone: String = "",
    val email: String = "",
    val city: String = "",
    val country: String = "",
    val birthday: String = "",
) {
    val displayName: String get() = listOf(givenName, surname).filter { it.isNotBlank() }.joinToString(" ").trim()

    val emailValid: Boolean get() = ContactFieldValidation.isValidEmail(email)
    val phoneValid: Boolean get() = ContactFieldValidation.isValidPhone(phone)
    val odinIdValid: Boolean get() = ContactFieldValidation.isValidOdinId(odinId)

    /** Has at least one meaningful field AND every non-empty field is well-formed. */
    val isSavable: Boolean
        get() = (givenName.isNotBlank() || surname.isNotBlank() ||
            phone.isNotBlank() || email.isNotBlank() || odinId.isNotBlank()) &&
            emailValid && phoneValid && odinIdValid
}

fun ContactBookEntry.toDraft(): ContactDraft = ContactDraft(
    givenName = givenName.orEmpty().ifBlank { if (surname.isNullOrBlank()) displayName else "" },
    surname = surname.orEmpty(),
    odinId = odinId.orEmpty(),
    phone = phone.orEmpty(),
    email = email.orEmpty(),
    city = city.orEmpty(),
    country = country.orEmpty(),
    birthday = birthday.orEmpty(),
)

/** Progressive state of the device-import flow (mobile only). */
sealed interface ImportUiState {
    data object RequestingPermission : ImportUiState
    data object Reading : ImportUiState
    data class Review(
        val contacts: List<DeviceContact>,
        val selected: Set<Int>,
    ) : ImportUiState
    data class Saving(val done: Int, val total: Int) : ImportUiState
    data class Complete(val imported: Int, val skipped: Int) : ImportUiState
    data class Failed(val reason: ContactBookError) : ImportUiState
}

@Immutable
data class ContactBookUiState(
    /** Already filtered + searched + A–Z sorted. */
    val contacts: List<ContactBookEntry> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val filter: ContactFilter = ContactFilter.ALL,
    val overlay: ContactBookOverlay? = null,
    val importState: ImportUiState? = null,
    val importSupported: Boolean = false,
)

sealed interface ContactBookUiAction {
    data class SearchChanged(val query: String) : ContactBookUiAction
    data class FilterChanged(val filter: ContactFilter) : ContactBookUiAction
    data class ContactClicked(val entry: ContactBookEntry) : ContactBookUiAction
    data object AddClicked : ContactBookUiAction
    data class EditClicked(val entry: ContactBookEntry) : ContactBookUiAction
    data class DeleteClicked(val entry: ContactBookEntry) : ContactBookUiAction
    data class SaveContact(
        val draft: ContactDraft,
        val editing: ContactBookEntry?,
        val photo: PlatformFile? = null,
    ) : ContactBookUiAction
    data class MessageClicked(val entry: ContactBookEntry) : ContactBookUiAction
    data class SyncClicked(val entry: ContactBookEntry) : ContactBookUiAction
    data object CloseOverlay : ContactBookUiAction

    // Import flow
    data object ImportClicked : ContactBookUiAction
    data class ImportPermissionResult(val granted: Boolean) : ContactBookUiAction
    data class ImportToggle(val index: Int) : ContactBookUiAction
    data class ImportSelectAll(val selected: Boolean) : ContactBookUiAction
    data object ImportConfirm : ContactBookUiAction
    data object ImportDismiss : ContactBookUiAction

    // Onboarding (first run)
    data object OnboardingGetStarted : ContactBookUiAction
    data object OnboardingSkip : ContactBookUiAction
}

sealed interface ContactBookUiEvent {
    /** Ask the screen (which owns the PermissionsManager) to prompt for CONTACTS. */
    data object RequestContactsPermission : ContactBookUiEvent
    /** Bridge into chat for a contact that has an odinId. */
    data class OpenChat(val odinId: String) : ContactBookUiEvent
    data class Error(val error: ContactBookError) : ContactBookUiEvent
    /** User skipped onboarding — pop back out of the contacts tab. */
    data object CloseOnboarding : ContactBookUiEvent
}

enum class ContactBookError {
    SaveFailed,
    DeleteFailed,
    ImportFailed,
    PhotoFailed,
    PermissionDenied,
}
