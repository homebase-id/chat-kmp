package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.files.ExportDestination
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.core.filesync.FilesyncProtocol
import kotlin.uuid.Uuid

class FilesyncFolder(val nodes: List<FilesyncNode>, val payloads: Map<Uuid, FilesyncPayload>)

/** Network only: the drive is never synced, so every listing is one folder fetched fresh. */
class FilesyncRepository(
    private val driveQueryProvider: DriveQueryProvider,
    private val payloadDownloadService: PayloadDownloadService,
) {

    suspend fun listFolder(folderId: Uuid): FilesyncFolder {
        val nodes = mutableListOf<FilesyncNode>()
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
                nodes += node
                if (payload != null) payloads[node.nodeId] = payload
            }
            val next = response.cursorState?.takeIf { response.hasMoreRows && it != cursor }
            cursor = next
        } while (next != null)

        return FilesyncFolder(
            nodes = nodes.sortedWith(compareBy<FilesyncNode> { !it.isFolder }.thenBy { it.name.lowercase() }),
            payloads = payloads,
        )
    }

    /** Decrypted and hash-checked into a disposable temp file; throws PayloadDamagedException on a bad hash. */
    suspend fun download(node: FilesyncNode, payload: FilesyncPayload, onProgress: (Float) -> Unit): String? =
        payloadDownloadService.exportVerified(
            driveId = FilesyncProtocol.DriveAlias,
            fileId = payload.fileId,
            key = FilesyncProtocol.PayloadKey,
            keyHeader = payload.payloadKey,
            hash = payload.hash,
            destination = ExportDestination.UploadTemp("fs_", extensionOf(node.name)),
            onProgress = onProgress,
        )

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 && '/' !in it }
            ?.let { ".$it" }
            .orEmpty()

    private companion object {
        const val PAGE_SIZE = 1000
    }
}
