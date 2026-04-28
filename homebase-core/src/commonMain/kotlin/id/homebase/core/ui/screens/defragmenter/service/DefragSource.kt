package id.homebase.core.ui.screens.defragmenter.service

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Data backend for the Defragmenter screen.
 *
 * [analyze] streams progress so the UI can render the scan as it happens: a
 * [DefragAnalyzeEvent.Sized] with the final grid dimensions, zero or more
 * [DefragAnalyzeEvent.Progress] chunks (each carrying per-position [CellState]
 * for the rows scanned in that chunk), and a terminal [DefragAnalyzeEvent.Done]
 * carrying the final cell map plus any [QuarantineCandidate]s the user should
 * be prompted about.
 *
 * When a defrag animation commits a block into a [CellState.SoftDeleted] cell,
 * the ViewModel calls [hardDelete] with the `fileId` that position represents.
 *
 * [repair] runs an integrity-fix pass that re-projects local SQL columns
 * for rows the classifier flagged as `LegacyUserDateZero` or
 * `SoftDeleteArchivalMismatch`. **Local-only for now** — see the big TODO
 * in [LiveDefragSource.repair] re: the server-side download-verify-patch-
 * upload pass that's needed to make `SoftDeleteArchivalMismatch` repairs
 * survive a logout/wipe-resync cycle. Each successfully repaired row
 * emits a per-cell [DefragRepairEvent.Repaired] so the UI can flip its
 * colour back to Healthy one cell at a time.
 */
interface DefragSource {
    fun analyze(): Flow<DefragAnalyzeEvent>
    fun repair(): Flow<DefragRepairEvent>
    suspend fun hardDelete(driveId: Uuid, fileId: Uuid): Boolean

    /** Compacts the SQLite database file (runs VACUUM). */
    suspend fun vacuum()
}

sealed interface DefragAnalyzeEvent {
    /** Emitted once after drive counts have been summed, before jsonHeader scan starts. */
    data class Sized(val totalBlocks: Int) : DefragAnalyzeEvent

    /**
     * Emitted per scan chunk. [analyzedUpto] is the exclusive upper bound of
     * positions that have now been examined.
     *
     * [newCells] carries the [CellState] for each position discovered in this
     * chunk only — the caller accumulates them into the full grid for colored
     * rendering.
     *
     * [newGaps] is a backward-compat projection of [newCells] containing only
     * the [CellState.SoftDeleted] entries. The existing soft-delete /
     * hard-delete animation reads this directly without caring about the new
     * states. New consumers should read [newCells] and project as needed.
     */
    data class Progress(
        val analyzedUpto: Int,
        val newCells: Map<Int, CellState>,
        val newGaps: Map<Int, DeletedFileRef>,
    ) : DefragAnalyzeEvent

    /**
     * Terminal event. [cellMap] is the full accumulated per-position state.
     * [gapMap] is the soft-delete-only projection (backward compat — see
     * [Progress.newGaps]).
     *
     * [corruptCandidates] are rows whose `jsonHeader` failed strict
     * deserialisation; the UI walks these one at a time via the corrupt-JSON
     * prompt flow so the user can choose to hard-delete or skip each one.
     */
    data class Done(
        val totalBlocks: Int,
        val cellMap: Map<Int, CellState>,
        val gapMap: Map<Int, DeletedFileRef>,
        val corruptCandidates: List<QuarantineCandidate>,
    ) : DefragAnalyzeEvent
}

/**
 * One scanned row's classification. Drives the Defragmenter grid colour and
 * (for [LegacyUserDateZero] / [SoftDeleteArchivalMismatch]) determines whether
 * a row gets re-uploaded by [DefragSource.repair].
 *
 * Evaluation order in the classifier (first match wins):
 *  1. jsonHeader fails strict deserialise → [CorruptJsonHeader]
 *  2. fileType=8888 + ConversationMapper.mapToBasic throws → [UnmappableConversation]
 *  3. isSoftDeleted + archivalStatus drift → [SoftDeleteArchivalMismatch] (msg only)
 *     else                                  → [SoftDeleted] (existing semantic)
 *  4. fileType=7878 + userDate=0 + appData.userDate=null + created>0 → [LegacyUserDateZero]
 *  5. otherwise → [Healthy]
 */
sealed interface CellState {
    object Healthy : CellState

    /** Soft-deleted (either via fileState or archivalStatus). UI: red. */
    data class SoftDeleted(val ref: DeletedFileRef) : CellState

    /** Soft-deleted in jsonHeader but `archivalStatus != Removed` in SQL. UI: orange. */
    data class SoftDeleteArchivalMismatch(
        val driveId: Uuid,
        val fileId: Uuid,
        val rowId: Long,
    ) : CellState

    /** Chat message with `appData.userDate = null` projected as 0. UI: amber. */
    data class LegacyUserDateZero(
        val driveId: Uuid,
        val fileId: Uuid,
        val rowId: Long,
        val createdMs: Long,
    ) : CellState

    /** jsonHeader failed strict deserialise. UI: magenta. Drives the prompt. */
    data class CorruptJsonHeader(
        val driveId: Uuid,
        val fileId: Uuid,
        val rowId: Long,
    ) : CellState

    /** Conversation file (8888) where ConversationMapper.mapToBasic throws. UI: pink. */
    data class UnmappableConversation(
        val driveId: Uuid,
        val fileId: Uuid,
        val rowId: Long,
    ) : CellState
}

/**
 * Salvaged metadata for a [CellState.CorruptJsonHeader] row — populated via a
 * lenient JSON-element pass when strict deserialisation has already failed.
 * Best-effort: any field can be null if the JSON is too damaged to extract.
 * `fileId` and `rowId` are always available because they're SQL columns on the
 * row, not inside the (corrupt) JSON.
 */
data class QuarantineCandidate(
    val driveId: Uuid,
    val fileId: Uuid,
    val rowId: Long,
    val deserialiseError: String?,
    val senderOdinId: String?,
    val originalAuthor: String?,
    val createdMs: Long?,
    val fileType: Int?,
    val uniqueId: Uuid?,
    val rawHeaderPrefix: String,
)

sealed interface DefragRepairEvent {
    /** Emitted once before the repair scan starts. */
    data class Started(val eligibleEstimate: Int) : DefragRepairEvent

    /**
     * Emitted per row successfully repaired. The UI uses this to flip the
     * matching grid cell from its issue colour back to the healthy palette
     * one at a time, mirroring the per-position emission cadence.
     */
    data class Repaired(
        /** Position in the global grid, matching the analyze pass's cell map. */
        val position: Int,
        val driveId: Uuid,
        val fileId: Uuid,
        val rowId: Long,
        val kind: RepairKind,
    ) : DefragRepairEvent

    /** Terminal event. */
    data class Done(
        val analyzed: Int,
        val repaired: Int,
        val repairedLegacyUserDateZero: Int,
        val repairedSoftDeleteArchivalMismatch: Int,
        val skipped: Int,
    ) : DefragRepairEvent

    /** Which kind of repair was applied to a given row. */
    enum class RepairKind { LegacyUserDateZero, SoftDeleteArchivalMismatch }
}

data class DeletedFileRef(val driveId: Uuid, val fileId: Uuid)
