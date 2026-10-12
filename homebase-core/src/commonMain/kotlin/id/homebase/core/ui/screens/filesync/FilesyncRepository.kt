package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.files.ExportDestination
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.filesync.FilesyncProtocol
import kotlin.uuid.Uuid

class FilesyncFolder(val nodes: List<FilesyncNode>, val payloads: Map<Uuid, FilesyncPayload>)

/** Network only: the drive is never synced, so every listing is one folder fetched fresh. */
class FilesyncRepository(
    private val driveQueryProvider: DriveQueryProvider,
    private val payloadDownloadService: PayloadDownloadService,
    private val fileOperationsProvider: FileOperationsProvider,
) {

    suspend fun listFolder(folderId: Uuid): FilesyncFolder {
        // Keyed by node: a node changed between two pages comes back on both.
        val nodes = linkedMapOf<Uuid, FilesyncNode>()
        val payloads = mutableMapOf<Uuid, FilesyncPayload>()
        var cursor: String? = null
        do {
            val response = driveQueryProvider.queryBatch(
                driveId = FilesyncProtocol.DriveAlias,
                request = QueryBatchRequest(
                    queryParams = FileQueryParams(
                        fileType = listOf(FilesyncProtocol.FolderFileType, FilesyncProtocol.FileFileType),
                        fileState = listOf(FileState.Active),
                        groupId = listOf(folderId),
                    ),
                    resultOptionsRequest = QueryBatchResultOptionsRequest(
                        cursorState = cursor,
                        maxRecords = PAGE_SIZE,
                        includeMetadataHeader = true,
                    ),
                ),
            )
            response.searchResults.forEach { file ->
                val (node, payload) = file.toFilesyncNode() ?: return@forEach
                nodes[node.nodeId] = node
                if (payload != null) payloads[node.nodeId] = payload
            }
            val next = response.cursorState?.takeIf { response.hasMoreRows && it != cursor }
            cursor = next
        } while (next != null)

        return FilesyncFolder(
            nodes = nodes.values.sortedWith(compareBy<FilesyncNode> { !it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            payloads = payloads,
        )
    }

    /**
     * A file to open goes to share_outbound, swept like every other copy handed to another app;
     * one to save goes to upload-temp and is [discard]ed once the OS has its copy.
     */
    suspend fun download(name: String, payload: FilesyncPayload, forOpen: Boolean, onProgress: (Float) -> Unit): String? =
        payloadDownloadService.exportVerified(
            driveId = FilesyncProtocol.DriveAlias,
            fileId = payload.fileId,
            key = FilesyncProtocol.PayloadKey,
            keyHeader = payload.payloadKey,
            hash = payload.hash,
            destination = if (forOpen) {
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
    }
}
