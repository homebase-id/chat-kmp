@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.runtime.Immutable
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.contacts.ContactExperience
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.conversationsettings.ConversationOverview
import id.homebase.chat.conversationsettings.GroupInCommonItem
import id.homebase.chat.conversationsettings.SharedMediaItem
import id.homebase.core.ui.screens.contactbook.ContactDraft
import id.homebase.core.ui.screens.contactbook.RequestDirection
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A pending destructive action awaiting confirmation. */
enum class ContactDetailConfirm { BLOCK, DISCONNECT, DELETE }

@Immutable
data class ContactDetailUiState(
    val entry: ContactBookEntry? = null,
    val isLoading: Boolean = true,
    /** Connection status for this contact's odinId; null when not a connection / unknown. */
    val connectionStatus: ConnectionStatus? = null,
    /** User-defined circles this contact belongs to (system circles excluded), A–Z. */
    val circles: List<String> = emptyList(),
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
    /** Accept an incoming connection request from this contact. */
    data object AcceptRequestClicked : ContactDetailAction
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
}

sealed interface ContactDetailEvent {
    data class OpenConversation(val conversationId: Uuid) : ContactDetailEvent
    data class SeeAllMedia(val conversationId: String) : ContactDetailEvent
    data object Back : ContactDetailEvent
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
}
