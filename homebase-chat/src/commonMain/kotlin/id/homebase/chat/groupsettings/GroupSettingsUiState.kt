package id.homebase.chat.groupsettings

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.files.TransferStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.StringResource

@Immutable
data class GroupSettingsUiState(
    val isLoading: Boolean = true,
    val currentOdinId: OdinId? = null,
    val conversation: ConversationUiModel? = null,
    val isCurrentUserGroupAdmin: Boolean = false,
    val isLegacyGroup: Boolean = false,
    val contacts: List<ContactUiModel> = listOf(),
    /** Per-recipient transfer state for the main conversation file. null = caller is not
     *  the original author and the column should be hidden. */
    val mainFileTransfer: Map<OdinId, RecipientFileStatus>? = null,
    /** Per-recipient transfer state for the admin file. null = column hidden (see above). */
    val adminFileTransfer: Map<OdinId, RecipientFileStatus>? = null,
    val isHealing: Boolean = false,
    /** Members with an in-flight server op (make/remove admin, remove from group). The
     *  member-action sheet swaps its action rows for a spinner while the OdinId is
     *  in this set, then clears it on completion. */
    val pendingMemberOps: Set<OdinId> = emptySet(),
    /** True while leaveGroup is awaiting server completion. Drives the full-screen
     *  overlay. Cleared on error; on success the screen pops via the [Back] event. */
    val isLeaving: Boolean = false,
    /** Local-only DB-vs-own-server diagnostic for this group's two files (main + admin).
     *  Populated by [GroupSettingsViewModel.loadTransferHistory]; null while loading and
     *  on non-group / legacy-group conversations (legacy groups inline admins, so a
     *  separate "DB-admin: absent" row would be a false positive). */
    val filesDiagnostic: GroupFilesDiagnostic? = null,
    val uiEvent: GroupSettingsUiEvent? = null,
    val uiDialog: GroupSettingsUiDialog? = null,
    val uiSheet: GroupSettingsUiSheet? = null,
) {
    val canHeal: Boolean get() = mainFileTransfer != null || adminFileTransfer != null
}

@Immutable
sealed interface RecipientFileStatus {
    data object Ok : RecipientFileStatus
    data class Problem(val rawStatus: TransferStatus, val detailRes: StringResource?) : RecipientFileStatus
}

/**
 * Local-DB state of one of the two group files (main conversation file or
 * admin file). Three states; [Placeholder] is the local-only marker written
 * by `OptimisticWriter.writeLocalOnlyConversationPlaceholder` /
 * `writeLocalOnlyAdminPlaceholder` (versionTag=null) — server has no copy
 * yet, peer push from canonical author will replace it.
 */
@Immutable
sealed interface DbFileRow {
    data class Present(
        val versionTag: Uuid,
        val originalAuthor: OdinId,
        val fileId: Uuid,
    ) : DbFileRow

    data class Placeholder(val fileId: Uuid) : DbFileRow

    data object Absent : DbFileRow
}

/**
 * Server-side state, derived from [DbFileRow]: post-sync, local DB and own
 * server agree except for placeholders. From the server's point of view a
 * [DbFileRow.Placeholder] is indistinguishable from absent.
 */
@Immutable
sealed interface ServerFileRow {
    data class Present(
        val versionTag: Uuid,
        val originalAuthor: OdinId,
        val fileId: Uuid,
    ) : ServerFileRow

    data object Absent : ServerFileRow
}

/** The four cells of the group-info diagnostic block. */
@Immutable
data class GroupFilesDiagnostic(
    val conversationId: Uuid,
    val expectedMainUniqueId: Uuid,
    val expectedAdminUniqueId: Uuid,
    val dbGroup: DbFileRow,
    val dbAdmin: DbFileRow,
    val serverGroup: ServerFileRow,
    val serverAdmin: ServerFileRow,
) {
    val allHealthy: Boolean
        get() = dbGroup is DbFileRow.Present &&
                dbAdmin is DbFileRow.Present &&
                serverGroup is ServerFileRow.Present &&
                serverAdmin is ServerFileRow.Present
}

sealed interface GroupSettingsUiEvent {
    data object Back : GroupSettingsUiEvent
    data class Error(val errorMessage: String) : GroupSettingsUiEvent
    data class ShowContactInfo(val odinId: String) : GroupSettingsUiEvent
    data class ShowAddMembers(val conversationId: String) : GroupSettingsUiEvent
    data class ShowEditGroup(val conversationId: String) : GroupSettingsUiEvent
    data class OpenUrl(val url: String) : GroupSettingsUiEvent
    data class HealCompleted(val mainHealed: Boolean, val adminHealed: Boolean) : GroupSettingsUiEvent
}

sealed interface GroupSettingsUiDialog {
    data object ConfirmLeave: GroupSettingsUiDialog
    data object LeaveChooseAdmin: GroupSettingsUiDialog
    /** Admin leaving a legacy group: the choose-new-admin path is unavailable on
     *  legacy groups (no admin management UI), so we surface a strong-warning
     *  confirmation that lets the user proceed anyway. The service-side
     *  leaveGroup already handles legacy groups via the local-only branch. */
    data object LeaveLegacyAdminWarning: GroupSettingsUiDialog
    data class MakeAdmin(val contact: ContactUiModel) : GroupSettingsUiDialog
    data class RemoveAdmin(val contact: ContactUiModel) : GroupSettingsUiDialog
    data class RemoveFromGroup(val contact: ContactUiModel) : GroupSettingsUiDialog
}

sealed interface GroupSettingsUiSheet {
    data class Member(val contactId: Uuid): GroupSettingsUiSheet
}


