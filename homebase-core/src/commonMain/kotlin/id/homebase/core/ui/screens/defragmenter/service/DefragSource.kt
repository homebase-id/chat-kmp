package id.homebase.core.ui.screens.defragmenter.service

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Data backend for the Defragmenter screen.
 *
 * [analyze] streams progress so the UI can render the scan as it happens: a
 * [DefragAnalyzeEvent.Sized] with the final grid dimensions, zero or more
 * [DefragAnalyzeEvent.Progress] chunks, and a terminal [DefragAnalyzeEvent.Done]
 * carrying the final gap map.
 *
 * When a defrag animation commits a block into a gap, the ViewModel looks up
 * that gap in the map and calls [hardDelete] with the `fileId` of the file
 * that position represents.
 */
interface DefragSource {
    fun analyze(): Flow<DefragAnalyzeEvent>
    suspend fun hardDelete(driveId: Uuid, fileId: Uuid): Boolean

    /** Compacts the SQLite database file (runs VACUUM). */
    suspend fun vacuum()
}

sealed interface DefragAnalyzeEvent {
    /** Emitted once after drive counts have been summed, before jsonHeader scan starts. */
    data class Sized(val totalBlocks: Int) : DefragAnalyzeEvent

    /**
     * Emitted per scan chunk. [analyzedUpto] is the exclusive upper bound of
     * positions that have now been examined. [newGaps] are gap positions
     * discovered in this chunk only — the caller accumulates them.
     */
    data class Progress(
        val analyzedUpto: Int,
        val newGaps: Map<Int, DeletedFileRef>,
    ) : DefragAnalyzeEvent

    /** Terminal event: total and full accumulated gap map. */
    data class Done(
        val totalBlocks: Int,
        val gapMap: Map<Int, DeletedFileRef>,
    ) : DefragAnalyzeEvent
}

data class DeletedFileRef(val driveId: Uuid, val fileId: Uuid)
