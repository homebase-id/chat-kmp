package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.ExportDestination
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.filesync.FilesyncProtocol
import kotlin.uuid.Uuid

/** Nodes with what writing or downloading each one needs. */
class FilesyncNodes(val nodes: Map<Uuid, FilesyncNode>, val handles: Map<Uuid, FilesyncHandle>) {
    operator fun plus(other: FilesyncNodes) = FilesyncNodes(nodes + other.nodes, handles + other.handles)

    fun only(ids: Set<Uuid>) = FilesyncNodes(nodes.filterKeys { it in ids }, handles.filterKeys { it in ids })
}

/** Network only: the drive is never synced, so every listing is fetched fresh. */
class FilesyncRepository(
    private val driveQueryProvider: DriveQueryProvider,
    private val payloadDownloadService: PayloadDownloadService,
    private val fileOperationsProvider: FileOperationsProvider,
) {

    /** A folder's nodes, unsorted; a node in Recently deleted keeps its groupId, so it lists only when [includeRemoved]. */
    suspend fun listFolder(folderId: Uuid, includeRemoved: Boolean = false): FilesyncNodes =
        queryAll(
            nodeParams(if (includeRemoved) listOf(ArchivalStatus.None, ArchivalStatus.Removed) else listOf(ArchivalStatus.None))
                .copy(groupId = listOf(folderId))
        )

    /** Every removed node across the drive, and the live ones below removed folders, level by level (see [FilesyncTrash]). */
    suspend fun listDeleted(): FilesyncNodes {
        var all = queryAll(nodeParams(listOf(ArchivalStatus.Removed)))
        var folders = all.nodes.values.filter { it.isFolder }.map { it.nodeId }
        while (folders.isNotEmpty()) {
            val live = queryChunked(folders, listOf(ArchivalStatus.None)) { copy(groupId = it) }
            folders = live.nodes.values.filter { it.isFolder && it.nodeId !in all.nodes }.map { it.nodeId }
            all += live
        }
        return all
    }

    /** The newest live files, newest first. */
    suspend fun listRecentlyAdded(limit: Int = RECENT_LIMIT): FilesyncNodes =
        query(
            nodeParams(listOf(ArchivalStatus.None)).copy(fileType = listOf(FilesyncProtocol.FileFileType)),
            QueryBatchResultOptionsRequest(
                maxRecords = limit,
                includeMetadataHeader = true,
                ordering = QueryBatchSortOrder.NewestFirst,
                sorting = QueryBatchSortField.CreatedDate,
            ),
        ).first

    /** The nodes among [nodeIds] that are in one of [status] now; one gone for good, or in another status, is left out. */
    suspend fun findNodes(nodeIds: Collection<Uuid>, vararg status: ArchivalStatus): FilesyncNodes =
        queryChunked(nodeIds.distinct().filter { it != FilesyncProtocol.RootNodeId }, status.toList()) { copy(clientUniqueIdAtLeastOne = it) }

    private suspend fun queryChunked(
        ids: List<Uuid>,
        status: List<ArchivalStatus>,
        by: FileQueryParams.(List<Uuid>) -> FileQueryParams,
    ): FilesyncNodes =
        ids.chunked(PAGE_SIZE).fold(FilesyncNodes(emptyMap(), emptyMap())) { all, chunk -> all + queryAll(nodeParams(status).by(chunk)) }

    /** Folder names for "in Photos" lines, live or removed alike; the top level is left to the caller. */
    suspend fun folderNames(folderIds: Collection<Uuid>, known: Map<Uuid, FilesyncNode>): Map<Uuid, String> {
        val missing = folderIds.filter { it !in known && it != FilesyncProtocol.RootNodeId }
        val live = if (missing.isEmpty()) emptyMap() else findNodes(missing, ArchivalStatus.None).nodes
        return (known + live).filterKeys { it in folderIds }.mapValues { it.value.name }
    }

    private fun nodeParams(status: List<ArchivalStatus>) = FileQueryParams(
        fileType = listOf(FilesyncProtocol.FolderFileType, FilesyncProtocol.FileFileType),
        fileState = listOf(FileState.Active),
        archivalStatus = status.map { it.value },
    )

    private suspend fun queryAll(params: FileQueryParams): FilesyncNodes {
        // Keyed by node: a node changed between two pages comes back on both.
        var all = FilesyncNodes(emptyMap(), emptyMap())
        var cursor: String? = null
        do {
            val (page, next) = query(
                params,
                QueryBatchResultOptionsRequest(cursorState = cursor, maxRecords = PAGE_SIZE, includeMetadataHeader = true),
            )
            all += page
            cursor = next?.takeIf { it != cursor }
        } while (cursor != null)
        return all
    }

    private suspend fun query(params: FileQueryParams, options: QueryBatchResultOptionsRequest): Pair<FilesyncNodes, String?> {
        val response = driveQueryProvider.queryBatch(
            driveId = FilesyncProtocol.DriveAlias,
            request = QueryBatchRequest(queryParams = params, resultOptionsRequest = options),
        )
        val nodes = linkedMapOf<Uuid, FilesyncNode>()
        val handles = mutableMapOf<Uuid, FilesyncHandle>()
        response.searchResults.forEach { file ->
            val (node, handle) = file.toFilesyncNode() ?: return@forEach
            nodes[node.nodeId] = node
            handles[node.nodeId] = handle
        }
        return FilesyncNodes(nodes, handles) to response.cursorState?.takeIf { response.hasMoreRows }
    }

    /**
     * A file to open goes to share_outbound, swept like every other copy handed to another app;
     * one to save goes to upload-temp and is [discard]ed once the OS has its copy.
     */
    suspend fun download(name: String, payload: FilesyncPayload, toShareOutbound: Boolean, onProgress: (Float) -> Unit): String? =
        payloadDownloadService.exportVerified(
            driveId = FilesyncProtocol.DriveAlias,
            fileId = payload.fileId,
            key = FilesyncProtocol.PayloadKey,
            keyHeader = payload.payloadKey,
            hash = payload.hash,
            destination = if (toShareOutbound) {
                ExportDestination.ShareOutbound(extensionOf(name))
            } else {
                ExportDestination.UploadTemp("fs_", extensionOf(name))
            },
            onProgress = onProgress,
        )

    // The name comes from any desktop OS; only a plain alphanumeric extension reaches the temp path.
    private fun extensionOf(name: String): String =
        FilesyncNames.split(name).second
            .takeIf { ext -> ext.length in 2..11 && ext.drop(1).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            .orEmpty()

    fun discard(path: String) {
        runCatching { fileOperationsProvider.deleteTempFile(path) }
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val RECENT_LIMIT = 100
    }
}
