@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.runtime.Immutable
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.chat.conversationsettings.ConversationOverview
import id.homebase.chat.conversationsettings.GroupInCommonItem
import id.homebase.chat.conversationsettings.SharedMediaItem
import id.homebase.core.ui.screens.contactbook.ContactDraft
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
    /** A management action (delete/block/disconnect/unblock) is running — show a blocking spinner. */
    val actionInProgress: Boolean = false,
) {
    val hasOdinId: Boolean get() = !entry?.odinId.isNullOrBlank()
    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.Connected
    val isBlocked: Boolean get() = connectionStatus == ConnectionStatus.Blocked
}

sealed interface ContactDetailAction {
    data object MessageClicked : ContactDetailAction
    data object SyncClicked : ContactDetailAction
    data object MakeEmergencyContactClicked : ContactDetailAction
    data object RemoveEmergencyContactClicked : ContactDetailAction
    data object EditClicked : ContactDetailAction
    data class SaveContact(val draft: ContactDraft, val photo: io.github.vinceglb.filekit.PlatformFile?) :
        ContactDetailAction
    data object CloseEdit : ContactDetailAction
    data object DeleteClicked : ContactDetailAction
    data object BlockClicked : ContactDetailAction
    data object UnblockClicked : ContactDetailAction
    data object DisconnectClicked : ContactDetailAction
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
    /** The contact was marked as an emergency contact (and the designation sent). */
    data object EmergencyContactSet : ContactDetailEvent
    /** The contact was removed as an emergency contact. */
    data object EmergencyContactRemoved : ContactDetailEvent
}
