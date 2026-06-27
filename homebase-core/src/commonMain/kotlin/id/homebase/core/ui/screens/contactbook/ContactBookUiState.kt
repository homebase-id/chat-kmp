package id.homebase.core.ui.screens.contactbook

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.Uuid

/** The two sections of the unified Contacts screen. */
enum class ContactTab { CONTACTS, CIRCLES }

/**
 * People-list pill: everyone, introduced connections, confirmed (direct) connections, or
 * pending connection requests (incoming + outgoing, merged).
 */
enum class ContactFilter { ALL, INTRODUCED, CONFIRMED, REQUESTS }

/** Which way a pending connection request points relative to the signed-in identity. */
enum class RequestDirection {
    /** Someone wants to connect with me (I can Accept / Reject). */
    INCOMING,
    /** I asked to connect with them (I can Cancel). */
    OUTGOING,
}

/**
 * A pending connection request projected onto a [ContactBookEntry] for the Requests pill. The
 * entry resolves to a saved contact when we have one, else a synthetic display-only entry for
 * the identity. [receivedAtMs] drives the newest-first ordering.
 */
@Immutable
data class PendingRequestEntry(
    val entry: ContactBookEntry,
    val direction: RequestDirection,
    val receivedAtMs: Long,
)

/** Members of one circle, shown in a sheet/dialog. */
@Immutable
data class CircleMembersUi(
    val circleName: String,
    val members: List<ContactBookEntry> = emptyList(),
    val isLoading: Boolean = true,
)

/** A sheet/dialog shown over the contact list. (Detail is a full-screen route now.) */
sealed interface ContactBookOverlay {
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

@Immutable
data class ContactBookUiState(
    val selectedTab: ContactTab = ContactTab.CONTACTS,
    /** Contacts tab: already filtered + searched + A–Z sorted. */
    val contacts: List<ContactBookEntry> = emptyList(),
    val totalCount: Int = 0,
    /** Domains (lowercased) that are connected — drives the "connected" badge. */
    val connectedOdinIds: Set<String> = emptySet(),
    /** Introduced filter: connections established via an introduction. */
    val introduced: List<ContactBookEntry> = emptyList(),
    /** Confirmed filter: direct connections (Connected, not via an introduction). */
    val confirmed: List<ContactBookEntry> = emptyList(),
    /** Requests filter: pending connection requests (incoming + outgoing), newest first. */
    val requests: List<PendingRequestEntry> = emptyList(),
    /** Lowercased contact-domain → introducer display name, for the "Introduced by" row line. */
    val introducedByDomain: Map<String, String> = emptyMap(),
    /** Circles tab. */
    val circles: List<CircleWithMembers> = emptyList(),
    val circlesLoading: Boolean = false,
    val circleMembers: CircleMembersUi? = null,
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val filter: ContactFilter = ContactFilter.ALL,
    val overlay: ContactBookOverlay? = null,
    /** Logged-in owner, for the header avatar that links to settings. Null until loaded. */
    val ownerSession: OwnerSession? = null,
    /** Connection state surfaced as the avatar's status dot. */
    val connectionStatus: AppConnectionStatus = AppConnectionStatus.Disconnected,
    /** A sync pass is in flight — the avatar shows a spinner instead of the dot. */
    val driveIsSyncing: Boolean = false,
    /** The last sync pass failed — the avatar dot turns amber. */
    val hasDriveError: Boolean = false,
)

sealed interface ContactBookUiAction {
    data class TabSelected(val tab: ContactTab) : ContactBookUiAction
    data class CircleClicked(val circle: CircleWithMembers) : ContactBookUiAction
    data object CircleMembersDismiss : ContactBookUiAction
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

    // Onboarding (first run)
    data object OnboardingGetStarted : ContactBookUiAction
    data object OnboardingSkip : ContactBookUiAction
}

sealed interface ContactBookUiEvent {
    /** Open (creating if needed) the 1:1 conversation with a contact, by id. */
    data class OpenConversation(val conversationId: Uuid) : ContactBookUiEvent
    /** Open the full-screen detail for a contact. */
    data class OpenDetail(val uniqueId: String, val odinId: String?) : ContactBookUiEvent
    /** Open the full-screen Add Contact flow (lead-with-Homebase-ID). */
    data object OpenAddContact : ContactBookUiEvent
    data class Error(val error: ContactBookError) : ContactBookUiEvent
    /** User skipped onboarding — pop back out of the contacts tab. */
    data object CloseOnboarding : ContactBookUiEvent
}

enum class ContactBookError {
    SaveFailed,
    SaveForbidden,
    DeleteFailed,
    PhotoFailed,
    MessageFailed,
    ClearUnsupported,
}
