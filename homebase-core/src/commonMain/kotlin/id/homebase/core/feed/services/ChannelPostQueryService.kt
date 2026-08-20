package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.CursoredResult
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import kotlin.uuid.Uuid

// The per-channel counterpart to FeedTimelineService. With [ownerOdinId] set it brokers a query-batch over
// peer, which returns HEADERS only — a remote payload-bytes read is deferred.
class ChannelPostQueryService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val driveQueryProvider: DriveQueryProvider,
) {

    companion object {
        private const val TAG = "ChannelPostQueryService"
        private const val DefaultPageSize = 10
    }

    /** [ownerOdinId] set reads a remote identity's channel over peer; null reads the user's own locally. */
    suspend fun getPosts(
        channelId: Uuid,
        type: PostType? = null,
        cursor: String? = null,
        pageSize: Int = DefaultPageSize,
        ownerOdinId: OdinId? = null,
    ): CursoredResult<List<FeedPostItem>> {
        val dataTypes = type?.let { listOf(it.toDataType()) }
        return if (ownerOdinId == null) {
            getLocal(channelId, dataTypes, cursor, pageSize)
        } else {
            getRemote(channelId, dataTypes, cursor, pageSize, ownerOdinId)
        }
    }

    private suspend fun getLocal(
        channelId: Uuid,
        dataTypes: List<Int>?,
        cursor: String?,
        pageSize: Int,
    ): CursoredResult<List<FeedPostItem>> {
        val active = credentialsManager.getActiveCredentials()
            ?: return CursoredResult(results = emptyList(), cursorState = "")
        val identityId = active.getIdentityId()

        val result = QueryBatch(identityId).queryBatchAsync(
            dbm = databaseManager,
            driveId = channelId,
            noOfItems = pageSize,
            cursor = cursor?.let { runCatching { decodeCursor(it) }.getOrNull() },
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            filetypesAnyOf = listOf(FeedProtocol.PostFileType),
            datatypesAnyOf = dataTypes,
        )
        val posts = result.records
            .filterNot { it.isSoftDeleted() }
            .mapNotNull { it.toFeedPostItem() }
        Logger.d(tag = TAG) { "getLocal: channel=$channelId page=${posts.size} more=${result.hasMoreRows}" }
        return CursoredResult(
            results = posts,
            cursorState = if (result.hasMoreRows) result.cursor.toJson() else "",
        )
    }

    private suspend fun getRemote(
        channelId: Uuid,
        dataTypes: List<Int>?,
        cursor: String?,
        pageSize: Int,
        ownerOdinId: OdinId,
    ): CursoredResult<List<FeedPostItem>> {
        val request = QueryBatchRequest(
            queryParams = FileQueryParams(
                fileType = listOf(FeedProtocol.PostFileType),
                dataType = dataTypes,
            ),
            resultOptionsRequest = QueryBatchResultOptionsRequest(
                cursorState = cursor,
                maxRecords = pageSize,
                includeMetadataHeader = true,
                ordering = QueryBatchSortOrder.NewestFirst,
                sorting = QueryBatchSortField.UserDate,
            ),
        )
        val response = driveQueryProvider.queryBatch(
            driveId = channelId,
            request = request,
            ownerOdinId = ownerOdinId,
        )
        val posts = response.searchResults
            .filterNot { it.isSoftDeleted() }
            .mapNotNull { it.toFeedPostItem() }
        Logger.d(tag = TAG) {
            "getRemote: owner=$ownerOdinId channel=$channelId page=${posts.size} more=${response.hasMoreRows}"
        }
        return CursoredResult(
            results = posts,
            cursorState = if (response.hasMoreRows) response.cursorState.orEmpty() else "",
        )
    }

    private fun decodeCursor(cursorState: String) = QueryBatchCursor.fromJson(cursorState)
}
