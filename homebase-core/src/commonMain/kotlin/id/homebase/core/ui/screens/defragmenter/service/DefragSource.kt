package id.homebase.core.ui.screens.defragmenter.service

import kotlin.uuid.Uuid

/**
 * Data backend for the Defragmenter screen.
 *
 * Analyze returns the current drive-index state compacted into one big linear
 * grid: total block count + a map of grid positions that are gaps (i.e. rows
 * whose `archivalStatus` is `Removed`, awaiting hard-delete on the server).
 *
 * When a defrag animation commits a block into a gap, the ViewModel looks up
 * that gap in the map and calls [hardDelete] with the `fileId` of the file
 * that position represents.
 */
interface DefragSource {
    suspend fun analyze(): DefragAnalysis
    suspend fun hardDelete(driveId: Uuid, fileId: Uuid): Boolean

    /** Compacts the SQLite database file (runs VACUUM). */
    suspend fun vacuum()
}

data class DefragAnalysis(
    val totalBlocks: Int,
    val gapMap: Map<Int, DeletedFileRef>,
) {
    companion object {
        val EMPTY = DefragAnalysis(totalBlocks = 0, gapMap = emptyMap())
    }
}

data class DeletedFileRef(val driveId: Uuid, val fileId: Uuid)
