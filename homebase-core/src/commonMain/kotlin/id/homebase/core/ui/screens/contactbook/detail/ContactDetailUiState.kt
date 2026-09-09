@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.runtime.Immutable
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.contacts.ContactExperience
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.conversationsettings.ConversationOverview
import id.homebase.chat.conversationsettings.GroupInCommonItem
import id.homebase.chat.conversationsettings.SharedMediaItem
import id.homebase.core.ui.screens.contactbook.CircleMembersUi
import id.homebase.core.ui.screens.contactbook.ContactDraft
import id.homebase.core.ui.screens.contactbook.RequestDirection
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import id.homebase.core.ui.screens.contactbook.CircleAccessState
import id.homebase.core.ui.screens.contactbook.ReviewCircleGroups

/** A pending destructive action awaiting confirmation. */
enum class ContactDetailConfirm { BLOCK, DISCONNECT, DELETE }

/** One circle chip on the contact-detail screen. [pending] means this contact's grant on that
 *  circle is still a sealed deposit, read from the connection's `accessGrant.pendingCircleIds`. */
data class ContactCircleUi(
    val id: String,
    val name: String,
    val pending: Boolean,
    /**
     * What the membership is actually delivering. Null where it isn't known — the review sheet
     * offers circles the contact is not in yet, so there is nothing to report there.
     */
    val accessState: CircleAccessState? = null,
    /** The owner's chosen emoji; often a ZWJ sequence, so it is carried and rendered whole. */
    val emoji: String? = null,
    /** The circle's own description, where its owner wrote one. */
    val description: String? = null,
    /** How many identities are already in it. Null where the caller didn't resolve membership. */
    val memberCount: Int? = null,
    /**
     * For [CircleAccessState.AwaitingApp]: the app that has to finish the enrolment, by name.
     * Null when the app was deleted, or when [awaitsOwner] — nobody owns it but you.
     */
    val awaitingAppName: String? = null,
    /** True when the awaiting circle has no owning app, so the owner is the one who must act. */
    val awaitsOwner: Boolean = false,
)

@Immutable
data class ContactDetailUiState(
    val entry: ContactBookEntry? = null,
    val isLoading: Boolean = true,
    /** Connection status for this contact's odinId; null when not a connection / unknown. */
    val connectionStatus: ConnectionStatus? = null,
    /**
     * The contact's access has been switched off wholesale. Still connected and still in circles,
     * so nothing else on this screen would show it.
     */
    val isAccessRevoked: Boolean = false,
    /** Connected but never reviewed — the one state with something for the owner to do. */
    val needsReview: Boolean = false,
    /** Dark launch: the review's entry points are hidden until the dev flag is on. */
    val reviewEnabled: Boolean = false,
    /** Circles the review sheet offers, in its three groups. */
    val reviewCircleGroups: ReviewCircleGroups = ReviewCircleGroups(),
    /** Non-null while the review sheet is open. */
    val review: ReviewSheetState? = null,
    /** Non-null while the un-review confirmation is open. */
    val unreview: UnreviewState? = null,
    /** User-defined circles this contact belongs to, real or pending (system circles excluded), A–Z. */
    val circles: List<ContactCircleUi> = emptyList(),
    /** All user-defined circles the signed-in user could add a contact to (system circles excluded),
     *  A–Z. Independent of this contact's membership — used by the pending-request circle picker to
     *  choose which circles to grant on Accept (#921 Part B). */
    val assignableCircles: List<ContactCircleUi> = emptyList(),
    /** Open circle-detail dialog (tapped a chip in [circles]), or null when dismissed. View-only
     *  from this screen — [CircleMembersUi.manageable] is always false here. */
    val circleDetail: CircleMembersUi? = null,
    /** The existing 1:1 conversation, if one exists (never created just to view details). */
    val conversationId: Uuid? = null,
    val overview: ConversationOverview? = null,
    val overviewLoading: Boolean = false,
    val groupsInCommon: List<GroupInCommonItem> = emptyList(),
    val fullScreenMedia: SharedMediaItem? = null,
    val editOpen: Boolean = false,
    val confirm: ContactDetailConfirm? = null,
    /** A management action (delete/block/disconnect/unblock/request) is running — blocking spinner. */
    val actionInProgress: Boolean = false,
    /**
     * Non-null when there's a pending connection request for this contact: INCOMING (they want to
     * connect — Accept/Reject) or OUTGOING (we asked — Cancel). Drives the request action buttons.
     */
    val requestDirection: RequestDirection? = null,
    /** True when this contact is the logged-in identity's own (self) contact. */
    val isSelf: Boolean = false,
    /** Display name of the identity that introduced this contact, when the connection
     *  originated from an introduction. Null otherwise. */
    val introducedByName: String? = null,
    /**
     * `modified` timestamp of the newest file on this contact's location drive, captured by the last
     * temporal-access preflight (run on Sync). Non-null only after a successful verify that returned
     * access AND real data — it's how the user judges "how fresh is the data I can see?". Null when we
     * haven't verified, have no access, or the drive is empty.
     */
    val locateNewestDataAt: UnixTimeUtc? = null,
    /**
     * The synced "Experience" attribute from the contact's on-demand `ext_data` payload (title /
     * full bio / link). Null until loaded, or when the contact has none.
     */
    val experience: ContactExperience? = null,
    /** Decrypted bytes of the Experience image (its `experience_image` payload), if any. */
    val experienceImage: ByteArray? = null,
) {
    val hasOdinId: Boolean get() = !entry?.odinId.isNullOrBlank()
    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.Connected
    val isBlocked: Boolean get() = connectionStatus == ConnectionStatus.Blocked

    /**
     * A pending incoming request from someone we're not connected to yet. In this state the
     * detail body's connection-scoped sections (contact fields, groups-in-common, circles) and
     * the Activity/About tabs are all empty placeholders — so the screen instead shows the
     * requester's public profile to inform the Accept/Reject decision (#921).
     */
    val isPendingIncoming: Boolean
        get() = requestDirection == RequestDirection.INCOMING && !isConnected

    /** The "About" tab has content: a short bio, an Experience attribute (text/image), or socials. */
    val hasAboutContent: Boolean
        get() = !entry?.shortBio.isNullOrBlank() ||
            experienceImage != null ||
            experience?.let {
                !it.title.isNullOrBlank() || !it.fullBio.isNullOrEmpty() || !it.link.isNullOrBlank()
            } == true ||
            entry?.socialHandles?.isNotEmpty() == true

    /** The "Activity" tab has content: any shared media/files/audio/dice/locations in the 1:1. */
    val hasActivityContent: Boolean
        get() = overview?.let {
            it.media.isNotEmpty() || it.files.isNotEmpty() || it.audio.isNotEmpty() ||
                it.diceRolls.isNotEmpty() || it.locations.isNotEmpty()
        } == true
}

/** Open state for the review sheet, mirroring the contact book's. */
@Immutable
data class ReviewSheetState(
    val introducedBy: String? = null,
    /** When the connection was made, epoch-millis. */
    val connectedAtMs: Long? = null,
    val alreadyHeldCircleIds: Set<String> = emptySet(),
    val isSubmitting: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Open state for the un-review confirmation.
 *
 * [blockingCircles] non-empty means the server refused: the contact still holds circles a review
 * granted, and those have to go first. Named rather than counted, because "remove them from two
 * circles" doesn't tell you which two.
 */
@Immutable
data class UnreviewState(
    val isSubmitting: Boolean = false,
    val blockingCircles: List<String> = emptyList(),
    val failed: Boolean = false,
)

sealed interface ContactDetailAction {
    data object MessageClicked : ContactDetailAction
    data object SyncClicked : ContactDetailAction
    data object EditClicked : ContactDetailAction
    data class SaveContact(
        val draft: ContactDraft,
        val additionalPhones: List<String>,
        val additionalEmails: List<String>,
        val photo: io.github.vinceglb.filekit.PlatformFile?,
    ) : ContactDetailAction
    data object CloseEdit : ContactDetailAction
    data object DeleteClicked : ContactDetailAction
    data object BlockClicked : ContactDetailAction
    data object UnblockClicked : ContactDetailAction
    data object DisconnectClicked : ContactDetailAction
    /** Accept an incoming request and add the contact to the chosen circles (their 32-char
     *  N-format ids). Empty list = accept without adding to any circle (#921 Part B). */
    data class AcceptRequestClicked(val circleIds: List<String>) : ContactDetailAction
    /** Reject (decline) an incoming connection request from this contact. */
    data object RejectRequestClicked : ContactDetailAction
    /** Cancel (withdraw) an outgoing connection request to this contact. */
    data object CancelRequestClicked : ContactDetailAction
    data object ConfirmYes : ContactDetailAction
    data object ConfirmDismiss : ContactDetailAction
    data class OpenMedia(val item: SharedMediaItem) : ContactDetailAction
    data object CloseMedia : ContactDetailAction
    data object SeeAllMediaClicked : ContactDetailAction
    data class OpenGroup(val conversationId: Uuid) : ContactDetailAction
    data object BackClicked : ContactDetailAction
    /** Open the review sheet for a New connection. */
    data object ReviewClicked : ContactDetailAction
    /** Complete the review: stamp it and enrol [circleIds]. Empty = the "chat only" outcome. */
    data class ReviewSubmitted(val circleIds: Set<String>) : ContactDetailAction
    data object ReviewDismissed : ContactDetailAction
    /** Clear the review stamp, dropping the contact back to New. */
    data object UnreviewClicked : ContactDetailAction
    data object UnreviewConfirmed : ContactDetailAction
    data object UnreviewDismissed : ContactDetailAction
    /** Tapped a circle chip — opens the circle-detail dialog for [circleId]. */
    data class CircleClicked(val circleId: String) : ContactDetailAction
    data object CircleDetailDismiss : ContactDetailAction
    /** Tapped another contact's row inside the circle-detail dialog. */
    data class CircleMemberClicked(val entry: ContactBookEntry) : ContactDetailAction
}

sealed interface ContactDetailEvent {
    data class OpenConversation(val conversationId: Uuid) : ContactDetailEvent
    data class SeeAllMedia(val conversationId: String) : ContactDetailEvent
    data object Back : ContactDetailEvent

    /**
     * The contact was deleted and the screen should close. Distinct from [Back] so the
     * contact book can clear its search query — the deleted contact may have been the
     * query's only match, and a plain back must NOT clear an in-progress search (#876).
     */
    data object DeletedAndBack : ContactDetailEvent
    data object Error : ContactDetailEvent
    /** 403 — app lacks manage-contacts permission. */
    data object Forbidden : ContactDetailEvent
    /** Generic/transient failure while deleting the contact. */
    data object DeleteError : ContactDetailEvent
    /** 403 on delete — app lacks manage-contacts permission. */
    data object DeleteForbidden : ContactDetailEvent
    /** 403 on block/unblock/disconnect — app lacks manage-connections permission. */
    data object ConnectionForbidden : ContactDetailEvent
    data object PhotoError : ContactDetailEvent
    /** Edit blanked a previously-set field, which the server merge can't express — field kept. */
    data object ClearUnsupported : ContactDetailEvent
    data object AdditionsFailed : ContactDetailEvent
    /** Success confirmations for connection actions. */
    data object Blocked : ContactDetailEvent
    data object Unblocked : ContactDetailEvent
    data object Disconnected : ContactDetailEvent
    /** Best-effort profile sync was requested; the enriched contact lands later via drive sync. */
    data object SyncStarted : ContactDetailEvent
    /** Connection-request action confirmations. */
    data object RequestAccepted : ContactDetailEvent
    data object RequestRejected : ContactDetailEvent
    data object RequestCancelled : ContactDetailEvent
    /** Accept failed because the sender already withdrew the request (server-confirmed gone). */
    data object RequestWithdrawn : ContactDetailEvent
    /** Tapped another contact's row inside the circle-detail dialog — navigate to their detail. */
    data class OpenOtherContact(val uniqueId: String, val odinId: String?) : ContactDetailEvent
}
