package id.homebase.core.ui.screens.defragmenter

import id.homebase.core.ui.screens.defragmenter.model.BlockGrid
import id.homebase.core.ui.screens.defragmenter.service.MessageContentQuarantineCandidate
import id.homebase.core.ui.screens.defragmenter.service.QuarantineCandidate

sealed interface DefragmenterPhase {
    data object Idle : DefragmenterPhase
    data object Analyzing : DefragmenterPhase
    data object Ready : DefragmenterPhase
    data object Defragmenting : DefragmenterPhase
    data object Paused : DefragmenterPhase
    /** Re-projecting local SQL columns for classifier-flagged rows. */
    data object Repairing : DefragmenterPhase
    data object Vacuuming : DefragmenterPhase
    data object Complete : DefragmenterPhase
    data object Cancelled : DefragmenterPhase
}

data class InFlightMove(
    val id: Long,
    val fromIndex: Int,
    val toIndex: Int,
    val startTimeNanos: Long,
    val durationNanos: Long,
)

data class DefragmenterUiState(
    val phase: DefragmenterPhase = DefragmenterPhase.Idle,
    val grid: BlockGrid = BlockGrid.EMPTY,
    val gridVersion: Long = 0L,
    val inFlight: List<InFlightMove> = emptyList(),
    val targetHighlights: IntArray = IntArray(0),
    val totalBlocks: Int = 0,
    val initialGaps: Int = 0,
    val gapsRemaining: Int = 0,
    val movesCompleted: Long = 0L,
    val progressFraction: Float = 0f,
    val elapsedMs: Long = 0L,
    val estRemainingMs: Long = 0L,
    // Streaming-analyze progress. analyzedUpto is the exclusive upper bound of
    // positions already scanned; analyzeTotal is the final grid size known
    // after the Sized event.
    val analyzedUpto: Int = 0,
    val analyzeTotal: Int = 0,
    // Per-cell state codes (length == totalBlocks; default 0 = Healthy). Filled
    // in by the analyze pipeline as classifier results arrive. The canvas reads
    // this to pick the per-block fill colour. Mutated in place — gridVersion
    // bumps trigger redraw. See model/CellStateCode.kt for the encoding.
    val cellStates: ByteArray = ByteArray(0),
    // Tally of non-Healthy classifier states for the stats panel. Updated on
    // every Progress emit so the user sees counts climb during the scan.
    val issueCountLegacyUserDateZero: Int = 0,
    val issueCountArchivalMismatch: Int = 0,
    val issueCountCorruptJson: Int = 0,
    val issueCountUnmappableConvo: Int = 0,
    val issueCountOrphanMessage: Int = 0,
    val issueCountCorruptMessageContent: Int = 0,
    // Review-walk state. corruptHeaderCandidates + corruptMessageCandidates
    // form a single unified queue (header candidates first); reviewIndex is
    // a pointer into that combined sequence. showReviewDialog gates the
    // CorruptFileReviewDialog mount in the screen.
    val corruptHeaderCandidates: List<QuarantineCandidate> = emptyList(),
    val corruptMessageCandidates: List<MessageContentQuarantineCandidate> = emptyList(),
    val reviewIndex: Int = 0,
    val showReviewDialog: Boolean = false,
) {
    /** True during Vacuuming and Complete — canvas tints filled blocks green. */
    val celebratory: Boolean
        get() = phase is DefragmenterPhase.Vacuuming || phase is DefragmenterPhase.Complete

    /** Index of the cell the scan head is on, or null outside of Analyzing. */
    val scanHeadIndex: Int?
        get() = if (phase is DefragmenterPhase.Analyzing && analyzedUpto > 0) analyzedUpto - 1 else null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DefragmenterUiState) return false
        return phase == other.phase &&
            grid === other.grid &&
            gridVersion == other.gridVersion &&
            inFlight === other.inFlight &&
            targetHighlights === other.targetHighlights &&
            totalBlocks == other.totalBlocks &&
            initialGaps == other.initialGaps &&
            gapsRemaining == other.gapsRemaining &&
            movesCompleted == other.movesCompleted &&
            progressFraction == other.progressFraction &&
            elapsedMs == other.elapsedMs &&
            estRemainingMs == other.estRemainingMs &&
            analyzedUpto == other.analyzedUpto &&
            analyzeTotal == other.analyzeTotal &&
            cellStates === other.cellStates &&
            issueCountLegacyUserDateZero == other.issueCountLegacyUserDateZero &&
            issueCountArchivalMismatch == other.issueCountArchivalMismatch &&
            issueCountCorruptJson == other.issueCountCorruptJson &&
            issueCountUnmappableConvo == other.issueCountUnmappableConvo &&
            issueCountOrphanMessage == other.issueCountOrphanMessage &&
            issueCountCorruptMessageContent == other.issueCountCorruptMessageContent &&
            corruptHeaderCandidates === other.corruptHeaderCandidates &&
            corruptMessageCandidates === other.corruptMessageCandidates &&
            reviewIndex == other.reviewIndex &&
            showReviewDialog == other.showReviewDialog
    }

    override fun hashCode(): Int {
        var result = phase.hashCode()
        result = 31 * result + gridVersion.hashCode()
        result = 31 * result + totalBlocks
        result = 31 * result + gapsRemaining
        result = 31 * result + movesCompleted.hashCode()
        result = 31 * result + progressFraction.hashCode()
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + analyzedUpto
        result = 31 * result + analyzeTotal
        result = 31 * result + issueCountLegacyUserDateZero
        result = 31 * result + issueCountArchivalMismatch
        result = 31 * result + issueCountCorruptJson
        result = 31 * result + issueCountUnmappableConvo
        result = 31 * result + issueCountOrphanMessage
        result = 31 * result + issueCountCorruptMessageContent
        result = 31 * result + reviewIndex
        result = 31 * result + showReviewDialog.hashCode()
        return result
    }
}

sealed interface DefragmenterUiAction {
    data object Analyze : DefragmenterUiAction
    data object Start : DefragmenterUiAction
    data object Pause : DefragmenterUiAction
    data object Resume : DefragmenterUiAction
    data object Cancel : DefragmenterUiAction
    data object Close : DefragmenterUiAction

    /** Open the corrupt-file review dialog at index 0. */
    data object OpenReview : DefragmenterUiAction

    /** Dismiss the dialog without changing the queue position. */
    data object DismissReview : DefragmenterUiAction

    /** Advance to the next candidate without deleting; close if past end. */
    data object ReviewSkip : DefragmenterUiAction

    /** Hard-delete the current candidate, advance, decrement matching tally. */
    data object ReviewDelete : DefragmenterUiAction
}

sealed interface DefragmenterUiEvent {
    data object CloseRequested : DefragmenterUiEvent
}
