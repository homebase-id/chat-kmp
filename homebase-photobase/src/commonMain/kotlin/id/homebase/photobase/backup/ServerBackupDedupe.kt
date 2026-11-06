package id.homebase.photobase.backup

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.uuid.Uuid

/** No local index in v1: the drive isn't synced, so the server is what knows. */
class ServerBackupDedupe(
    private val queryProvider: DriveQueryProvider,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) : BackupDedupe {
    override suspend fun existing(uniqueIds: List<Uuid>): Set<Uuid> {
        if (uniqueIds.isEmpty()) return emptySet()
        val page = queryProvider.queryBatch(
            photosDriveId,
            QueryBatchRequest(
                queryParams = FileQueryParams(
                    fileType = listOf(PhotobaseProtocol.PhotoFileType),
                    fileState = listOf(FileState.Active),
                    clientUniqueIdAtLeastOne = uniqueIds,
                ),
                resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = uniqueIds.size),
            ),
        )
        return page.searchResults.mapNotNull { it.fileMetadata.appData.uniqueId }.toSet()
    }
}
