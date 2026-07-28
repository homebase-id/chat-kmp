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
 * People-list pill: everyone, connections that haven't been explicitly confirmed yet
 * (auto-connected, introduced-but-not-confirmed, or a plain direct connection never confirmed),
 * or connections that have been explicitly confirmed (server-computed `vetted` flag).
 * Pending connection requests are no longer a pill — they surface as a section at the top of
 * the list instead (see [ContactBookUiState.requests]).
 */
enum class ContactFilter { ALL, UNVETTED, VETTED }

/** Which way a pending connection request points relative to the signed-in identity. */
enum class RequestDirection {
    /** Someone wants to connect with me (I can Accept / Reject). */
    INCOMING,
    /** I asked to connect with them (I can Cancel). */
    OUTGOING,
}

/**
 * A pending connection request projected onto a [ContactBookEntry] for the "Connection requests"
 * section at the top of the list. The entry resolves to a saved contact when we have one, else a
 * synthetic display-only entry for the identity. [receivedAtMs] drives the newest-first ordering.
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
    val circleId: String,
    val circleName: String,
    /** Whether this circle's membership can be managed here — false for the system-managed
     *  Confirmed/Auto-connected circles (see [id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID]
     *  / [id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID]), which are computed by the vetting
     *  flow rather than manually curated. */
    val manageable: Boolean = true,
    val members: List<ContactBookEntry> = emptyList(),
    val isLoading: Boolean = true,
    /**
     * Contacts whose grant on this circle is still a sealed deposit rather than a real [members]
     * entry — live-read via a per-contact `/connections/status` fan-out triggered when the sheet
     * opens (there is no bulk "list pending" endpoint), never cached across app restarts.
     */
    val pendingMembers: List<ContactBookEntry> = emptyList(),
    /** True while the open-triggered pending-status fan-out is in flight. */
    val pendingChecking: Boolean = false,
    /** uniqueIds currently being removed — drives a per-row spinner in place of the remove "X"
     *  so a tap has visible feedback while the call is in flight. */
    val removingMemberIds: Set<Uuid> = emptySet(),
    /** Drives this circle grants access to — sourced synchronously from the circle definition
     *  already loaded with [members], no extra network call. */
    val drives: List<CircleDriveUi> = emptyList(),
    /** Set when this sheet is opened "from one contact's perspective" (contact detail) — that
     *  contact's own status in this circle, shown as a header line. Null when opened from the
     *  Circles tab, where there's no single "viewer" contact. */
    val viewerStatus: CircleMemberStatus? = null,
    /** uniqueId of the [viewerStatus] contact — excluded from the rendered "who else is in this
     *  circle" roster so the viewer's own row (already summarized in the header) isn't repeated. */
    val viewerContactId: Uuid? = null,
)

enum class CircleMemberStatus { Member, Pending }

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
    /** Unvetted filter: connected but not confirmed (server-computed `vetted` flag is false). */
    val unvetted: List<ContactBookEntry> = emptyList(),
    /** Vetted filter: connected AND confirmed (server-computed `vetted` flag is true). */
    val vetted: List<ContactBookEntry> = emptyList(),
    /** Pending connection requests (incoming + outgoing), newest first. Rendered as a section at
     *  the top of the list (incoming only) rather than a separate pill. */
    val requests: List<PendingRequestEntry> = emptyList(),
    /** Count of incoming connection requests, unfiltered by search. */
    val incomingRequestCount: Int = 0,
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
    /** "Add member" tapped in the circle-members sheet — opens the picker for this circle. */
    data class CircleAddMemberClicked(val circleId: String, val circleName: String) : ContactBookUiAction
    /** Revoke [member]'s membership (real or still-pending) in the circle [circleId]. */
    data class CircleRemoveMemberClicked(
        val circleId: String,
        val member: ContactBookEntry,
    ) : ContactBookUiAction
    data class SearchChanged(val query: String) : ContactBookUiAction
    data class FilterChanged(val filter: ContactFilter) : ContactBookUiAction
    data class ContactClicked(val entry: ContactBookEntry) : ContactBookUiAction
    data object AddClicked : ContactBookUiAction
    data class EditClicked(val entry: ContactBookEntry) : ContactBookUiAction
    data class DeleteClicked(val entry: ContactBookEntry) : ContactBookUiAction
    data class SaveContact(
        val draft: ContactDraft,
        val editing: ContactBookEntry?,
        val additionalPhones: List<String> = emptyList(),
        val additionalEmails: List<String> = emptyList(),
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
    /** Open the generic circle-member picker for [circleId]/[circleName]. */
    data class OpenCircleMemberAdd(val circleId: String, val circleName: String) : ContactBookUiEvent
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
    CircleActionFailed,
}
