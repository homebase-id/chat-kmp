package id.homebase.photobase.backup

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.uuid.Uuid

/** Asks the server, not the device's copy: another device may have backed the photo up since this one last synced. */
class ServerBackupDedupe(
    private val queryProvider: DriveQueryProvider,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) : BackupDedupe {
    override suspend fun existing(uniqueIds: List<Uuid>): OnServer {
        if (uniqueIds.isEmpty()) return OnServer(emptySet())
        val (deleted, live) = queryProvider.queryBatch(photosDriveId, backedUpQuery(uniqueIds)).searchResults
            .partition { it.fileState == FileState.Deleted }
        return OnServer(live.uniqueIds(), deleted.uniqueIds())
    }

    private fun List<HomebaseFile>.uniqueIds() = mapNotNullTo(mutableSetOf()) { it.fileMetadata.appData.uniqueId }
}

internal fun backedUpQuery(uniqueIds: List<Uuid>) = QueryBatchRequest(
    queryParams = FileQueryParams(
        fileType = listOf(PhotobaseProtocol.PhotoFileType),
        fileState = listOf(FileState.Active, FileState.Deleted),
        clientUniqueIdAtLeastOne = uniqueIds,
    ),
    resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = uniqueIds.size),
)
