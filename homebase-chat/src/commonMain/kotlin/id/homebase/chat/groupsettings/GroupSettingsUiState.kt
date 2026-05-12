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
    /** Per-recipient live "does the peer hold this file right now?" check for
     *  the main conversation file, asking the user's own server via the V2
     *  `/peer/{odinId}/drives/{driveId}/files/by-uid/{uid}/exists` endpoint.
     *  Author-only; null = column hidden (caller is not the original author). */
    val mainFileExists: Map<OdinId, MemberFileExistsStatus>? = null,
    /** Same as [mainFileExists], for the admin file. */
    val adminFileExists: Map<OdinId, MemberFileExistsStatus>? = null,
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
    /**
     * True when the Heal button should be enabled.
     *
     * Requires (1) the caller is the original author of at least one of the
     * two group files — without authorship there's nothing to redistribute,
     * and (2) all peer-exists checks have resolved (no [MemberFileExistsStatus.Loading]
     * entries left). The second clause blocks the click until we have enough
     * data to compute a targeted plan; pressing earlier would either over-send
     * or fall back to the all-recipients legacy path mid-load. A `null` map
     * (the column is hidden because the caller is not the author of that file)
     * is fine — heal still works for the other file.
     */
    val canHeal: Boolean
        get() {
            val anyAuthored = mainFileTransfer != null || adminFileTransfer != null
            if (!anyAuthored) return false
            val mainHasLoading = mainFileExists?.values?.any { it is MemberFileExistsStatus.Loading } == true
            val adminHasLoading = adminFileExists?.values?.any { it is MemberFileExistsStatus.Loading } == true
            return !mainHasLoading && !adminHasLoading
        }
}

@Immutable
sealed interface RecipientFileStatus {
    data object Ok : RecipientFileStatus
    data class Problem(val rawStatus: TransferStatus, val detailRes: StringResource?) : RecipientFileStatus
}

/**
 * Per-member result of asking the user's own server "does this peer hold
 * this group file right now, and at what versionTag?". Populated by
 * [GroupSettingsViewModel.loadPeerFileExists] via [PeerDriveQueryProvider].
 *
 * Distinct from [RecipientFileStatus], which only reflects our local
 * outbox/transfer-history (i.e. whether *our* last send was acked). This
 * one is a live read of the peer's current state.
 */
@Immutable
sealed interface MemberFileExistsStatus {
    /** Call in flight. */
    data object Loading : MemberFileExistsStatus

    /** Peer reports no copy. A fresh send is safe — no heal path needed. */
    data object Missing : MemberFileExistsStatus

    /** Peer holds the file and its versionTag matches ours. Safe to overwrite. */
    data class InSync(val versionTag: Uuid) : MemberFileExistsStatus

    /** Peer holds a copy whose versionTag differs from (or is unknown to) us.
     *  Heal-request path required to converge. */
    data class Stale(val peerVersionTag: Uuid?) : MemberFileExistsStatus

    /** RPC failed (unauthorized, network, peer unreachable). Throwable is
     *  logged at the call site, not retained in state. */
    data object Error : MemberFileExistsStatus
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
    data class HealCompleted(
        val mainHealed: Boolean,
        val adminHealed: Boolean,
        val mainRecipientCount: Int = 0,
        val adminRecipientCount: Int = 0,
        val healMessageRecipientCount: Int = 0,
    ) : GroupSettingsUiEvent
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

    /**
     * Streams one row per (peer, action) while a heal is in flight. Rows are
     * created up-front from the [GroupHealService.HealPlan] so the user
     * sees the full list of intended work immediately, then each row
     * transitions Pending → InFlight → Done (or Failed / Skipped) as the
     * service emits its [GroupHealService.HealPhase] callbacks.
     *
     * [finished] flips to true when the heal call returns (success or fail);
     * the dialog then enables its close button.
     */
    data class HealProgress(
        val items: List<HealProgressItem>,
        val finished: Boolean,
    ) : GroupSettingsUiSheet
}

@Immutable
data class HealProgressItem(
    val key: String,
    val kind: HealActionKind,
    val peer: OdinId,
    val state: HealProgressState,
)

@Immutable
enum class HealActionKind {
    /** Pushing the main conversation file (with all current payloads) to a peer. */
    GroupFile,
    /** Pushing the admin file to a peer. */
    AdminFile,
    /** Asking a peer to clean up their copy of the main conversation file
     *  via [StatusMessage.GroupHealRequested]. One row per (peer, file)
     *  even though the underlying status message covers both files — lets
     *  the user see exactly which files were stale on which peer. All
     *  matching rows transition together when the single message sends. */
    HealRequestGroupFile,
    /** Asking a peer to clean up their copy of the admin file. */
    HealRequestAdminFile,
}

@Immutable
enum class HealProgressState {
    /** Plan row created but the underlying enqueue hasn't started yet. */
    Pending,
    /** Either: (1) the enqueue is in flight, or (2) the enqueue succeeded
     *  and we're polling transfer-history for the per-recipient outcome.
     *  In both cases the row shows a spinner. */
    InFlight,
    /** Server reports `TransferStatus.Delivered` for this recipient. */
    Done,
    /** Either the enqueue threw, or transfer-history reports a failed
     *  TransferStatus value with the recipient no longer in our outbox.
     *  We can't always distinguish *why* a peer rejected (e.g. vt mismatch
     *  surfaces as `RecipientIdentityReturnedBadRequest`), so the row just
     *  marks "did not get through this round". */
    Failed,
    /** Plan target where the heal service intentionally did nothing
     *  (caller is not the file author, the per-file target set is empty
     *  because every peer is already InSync, etc.). */
    Skipped,
    /** Polling window expired with the server still trying — the outbox
     *  may retry for hours, but for the dialog's purposes we stop watching.
     *  Distinct from Failed: the file might still land later. */
    StillQueued,
}


