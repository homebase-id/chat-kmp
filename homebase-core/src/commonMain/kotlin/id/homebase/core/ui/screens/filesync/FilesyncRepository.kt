package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.ExportDestination
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** Nodes with what writing or downloading each one needs. */
class FilesyncNodes(val nodes: Map<Uuid, FilesyncNode>, val handles: Map<Uuid, FilesyncHandle>) {
    operator fun plus(other: FilesyncNodes) = FilesyncNodes(nodes + other.nodes, handles + other.handles)
}

/** What a query asks for: Live is every status the computers sync, another app's 1 or 3 too; Removed is Recently deleted. */
enum class FilesyncStatus(internal val archivalStatus: List<Int>) {
    Live(listOf(ArchivalStatus.None.value, ArchivalStatus.Archived.value, 3)),
    Removed(listOf(ArchivalStatus.Removed.value)),
}

/** A tombstone is proof a file went for good; a header not found is only a first "not there". */
enum class FilesyncNotThere { Tombstone, NoHeader }

/** Network only: the drive is never synced, so every listing is fetched fresh. */
class FilesyncRepository(
    private val driveQueryProvider: DriveQueryProvider,
    private val payloadDownloadService: PayloadDownloadService,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
) {

    /** A folder's nodes, unsorted; a node in Recently deleted keeps its groupId, so it lists only when [includeRemoved]. */
    suspend fun listFolder(folderId: Uuid, includeRemoved: Boolean = false): FilesyncNodes =
        queryAll(folderParams(folderId, includeRemoved)).first

    suspend fun takenNames(folderId: Uuid): MutableSet<String> = FilesyncNames.takenKeys(folderId, listFolder(folderId).nodes.values)

    /** Whether [listFolder] would find anything in [folderId]. */
    suspend fun holdsAny(folderId: Uuid, includeRemoved: Boolean): Boolean =
        queryPage(folderParams(folderId, includeRemoved), QueryBatchResultOptionsRequest(maxRecords = 1)).searchResults.isNotEmpty()

    private fun folderParams(folderId: Uuid, includeRemoved: Boolean) =
        nodeParams(if (includeRemoved) listOf(FilesyncStatus.Live, FilesyncStatus.Removed) else listOf(FilesyncStatus.Live))
            .copy(groupId = listOf(folderId))

    /** Every removed node across the drive, and the server's clock when the listing began. */
    suspend fun listRemoved(): Pair<FilesyncNodes, Long> = queryAll(nodeParams(listOf(FilesyncStatus.Removed)))

    /** [removed], and the live nodes below removed folders, level by level (see [FilesyncTrash]). */
    suspend fun listDeleted(removed: FilesyncNodes? = null): FilesyncNodes {
        var all = removed ?: listRemoved().first
        var folders = all.nodes.values.filter { it.isFolder }.map { it.nodeId }
        while (folders.isNotEmpty()) {
            val live = queryChunked(folders, listOf(FilesyncStatus.Live)) { copy(groupId = it) }
            folders = live.nodes.values.filter { it.isFolder && it.nodeId !in all.nodes }.map { it.nodeId }
            all += live
        }
        return all
    }

    /** The tombstones last written before [beforeMs], by the server's clock, each at its version. */
    suspend fun listTombstones(beforeMs: Long): List<FileVersionTag> {
        val found = mutableListOf<FileVersionTag>()
        forEachPage(
            nodeParams(null, FileState.Deleted),
            QueryBatchResultOptionsRequest(maxRecords = PAGE_SIZE, ordering = QueryBatchSortOrder.OldestFirst, sorting = QueryBatchSortField.AnyChangeDate),
        ) { page ->
            val old = page.searchResults.takeWhile { it.fileMetadata.updated.milliseconds < beforeMs }
            old.mapNotNullTo(found) { it.fileVersionTag() }
            old.size == page.searchResults.size
        }
        return found
    }

    /**
     * The [folderIds] holding a file that isn't deleted, live or removed, of any type, ours or not, written before this
     * call, but [except] at their versions. A row that doesn't read (no groupId) is there all the same, so its chunk's
     * folders all hold.
     */
    suspend fun holding(folderIds: List<Uuid>, except: Set<FileVersionTag>): Set<Uuid> {
        // The server's queries leave out what changed in their last second.
        delay(HOLD_BACK)
        val holding = mutableSetOf<Uuid>()
        for (chunk in folderIds.chunked(PAGE_SIZE)) {
            forEachPage(FileQueryParams(groupId = chunk, fileState = listOf(FileState.Active)), QueryBatchResultOptionsRequest(maxRecords = PAGE_SIZE)) { page ->
                for (file in page.searchResults) {
                    if (file.fileVersionTag() in except) continue
                    val folder = file.fileMetadata.appData.groupId
                    if (folder == null) holding += chunk else holding += folder
                }
                !holding.containsAll(chunk)
            }
        }
        return holding
    }

    /** Of [fileIds] a write didn't find, those the identity doesn't have, and how it says so; one whose header is found is left out. */
    suspend fun notThere(fileIds: List<Uuid>): Map<Uuid, FilesyncNotThere> = buildMap {
        for (id in fileIds) {
            val header = driveFileProvider.getFileHeader(FilesyncProtocol.DriveAlias, id)
            when {
                header == null -> put(id, FilesyncNotThere.NoHeader)
                header.fileState == FileState.Deleted -> put(id, FilesyncNotThere.Tombstone)
            }
        }
    }

    /** The newest live files, newest first. */
    suspend fun listRecentlyAdded(limit: Int = RECENT_LIMIT): FilesyncNodes =
        nodesOf(
            queryPage(
                nodeParams(listOf(FilesyncStatus.Live)).copy(fileType = listOf(FilesyncProtocol.FileFileType)),
                QueryBatchResultOptionsRequest(
                    maxRecords = limit,
                    includeMetadataHeader = true,
                    ordering = QueryBatchSortOrder.NewestFirst,
                    sorting = QueryBatchSortField.CreatedDate,
                ),
            )
        )

    /** The nodes among [nodeIds] that are in one of [status] now; one gone for good, or in another status, is left out. */
    suspend fun findNodes(nodeIds: Collection<Uuid>, vararg status: FilesyncStatus): FilesyncNodes =
        queryChunked(nodeIds.distinct().filter { it != FilesyncProtocol.RootNodeId }, status.toList()) { copy(clientUniqueIdAtLeastOne = it) }

    private suspend fun queryChunked(
        ids: List<Uuid>,
        status: List<FilesyncStatus>,
        by: FileQueryParams.(List<Uuid>) -> FileQueryParams,
    ): FilesyncNodes =
        ids.chunked(PAGE_SIZE).fold(FilesyncNodes(emptyMap(), emptyMap())) { all, chunk -> all + queryAll(nodeParams(status).by(chunk)).first }

    /** Folder names for "in Photos" lines, live or removed alike; the top level is left to the caller. */
    suspend fun folderNames(folderIds: Collection<Uuid>, known: Map<Uuid, FilesyncNode>): Map<Uuid, String> {
        val missing = folderIds.filter { it !in known && it != FilesyncProtocol.RootNodeId }
        val live = if (missing.isEmpty()) emptyMap() else findNodes(missing, FilesyncStatus.Live).nodes
        return (known + live).filterKeys { it in folderIds }.mapValues { it.value.name }
    }

    private fun nodeParams(status: List<FilesyncStatus>?, state: FileState = FileState.Active) = FileQueryParams(
        fileType = listOf(FilesyncProtocol.FolderFileType, FilesyncProtocol.FileFileType),
        fileState = listOf(state),
        archivalStatus = status?.flatMap { it.archivalStatus },
    )

    // Keyed by node: a node changed between two pages comes back on both. The time is the server's, at the first page.
    private suspend fun queryAll(params: FileQueryParams): Pair<FilesyncNodes, Long> {
        var all = FilesyncNodes(emptyMap(), emptyMap())
        var readAtMs = Long.MAX_VALUE
        forEachPage(params, QueryBatchResultOptionsRequest(maxRecords = PAGE_SIZE, includeMetadataHeader = true)) { page ->
            readAtMs = minOf(readAtMs, page.queryTime.milliseconds)
            all += nodesOf(page)
            true
        }
        return all to readAtMs
    }

    /** Reads every page while [onPage] asks for the next. */
    private suspend fun forEachPage(params: FileQueryParams, options: QueryBatchResultOptionsRequest, onPage: (QueryBatchResponse) -> Boolean) {
        var cursor: String? = null
        do {
            val page = queryPage(params, options.copy(cursorState = cursor))
            val more = onPage(page)
            cursor = page.cursorState?.takeIf { more && page.hasMoreRows && it != cursor }
        } while (cursor != null)
    }

    private suspend fun queryPage(params: FileQueryParams, options: QueryBatchResultOptionsRequest): QueryBatchResponse =
        driveQueryProvider.queryBatch(
            driveId = FilesyncProtocol.DriveAlias,
            request = QueryBatchRequest(queryParams = params, resultOptionsRequest = options),
        )

    private fun nodesOf(page: QueryBatchResponse): FilesyncNodes {
        val nodes = linkedMapOf<Uuid, FilesyncNode>()
        val handles = mutableMapOf<Uuid, FilesyncHandle>()
        page.searchResults.forEach { file ->
            val (node, handle) = file.toFilesyncNode() ?: return@forEach
            nodes[node.nodeId] = node
            handles[node.nodeId] = handle
        }
        return FilesyncNodes(nodes, handles)
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
        val HOLD_BACK = 1100.milliseconds
        const val RECENT_LIMIT = 100
    }
}

private fun HomebaseFile.fileVersionTag() = fileMetadata.versionTag?.let { FileVersionTag(fileId, it) }
