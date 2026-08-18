package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Server-side backfill for windowed-synced conversations (#1223): when local history is
 * exhausted but the drive was fresh-synced with a [id.homebase.api.sync.DriveSyncPolicy]
 * window, older messages still exist on the server. [fetchOlderPage] pulls one page of
 * them and upserts locally with cursor = null so the drive's incremental sync cursor is
 * never touched.
 *
 * Completeness ("the server truly has nothing older") is tracked in memory per session:
 * cheap, self-healing, and reset on logout together with the windows themselves.
 */
class ChatServerHistory(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val driveQueryProvider: DriveQueryProvider,
) {
    data class OlderPage(val upsertedCount: Int, val serverHasMore: Boolean)

    private val chatDrive = chatTargetDrive.alias
    private val fileProcessor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
    private val mutex = Mutex()
    private val completedConversations = mutableSetOf<Uuid>()

    suspend fun mayHaveOlderHistory(conversationId: Uuid): Boolean =
        mutex.withLock { conversationId !in completedConversations }

    /**
     * Fetch up to [SERVER_HISTORY_PAGE_SIZE] messages strictly older than
     * [oldestLocalSqlUserDateMs] (null = empty local window → newest server page) and
     * upsert them into the local index. Overlap at a tied userDate boundary is expected
     * and harmless — the modified-timestamp guard in the upsert rejects rows we already
     * have. Network failures propagate; the caller resets its loading state and the
     * button stays pressable.
     */
    suspend fun fetchOlderPage(conversationId: Uuid, oldestLocalSqlUserDateMs: Long?): OlderPage {
        val creds = credentialsManager.requireActiveCredentials()
        val request = QueryBatchRequest(
            queryParams = FileQueryParams(
                fileType = listOf(ChatProtocol.MessageFileType),
                groupId = listOf(conversationId),
            ),
            resultOptionsRequest = QueryBatchResultOptionsRequest(
                cursorState = oldestLocalSqlUserDateMs?.let {
                    QueryBatchCursor.fromStartPoint(UnixTimeUtc(it)).toJson()
                },
                maxRecords = SERVER_HISTORY_PAGE_SIZE,
                includeMetadataHeader = true,
                includeTransferHistory = true,
                ordering = QueryBatchSortOrder.NewestFirst,
                sorting = QueryBatchSortField.UserDate,
            ),
        )
        val response = driveQueryProvider.queryBatch(chatDrive, request)

        val upserted = if (response.searchResults.isNotEmpty()) {
            fileProcessor.baseUpsertEntryZapZap(
                identityId = creds.getIdentityId(),
                driveId = chatDrive,
                fileHeaders = response.searchResults,
                cursor = null,
            ).size
        } else 0

        if (!response.hasMoreRows) {
            mutex.withLock { completedConversations.add(conversationId) }
        }
        Logger.i(tag = "ChatPaging") {
            "fetchOlderPage($conversationId) anchor=$oldestLocalSqlUserDateMs " +
                "fetched=${response.searchResults.size} upserted=$upserted serverHasMore=${response.hasMoreRows}"
        }
        return OlderPage(upsertedCount = upserted, serverHasMore = response.hasMoreRows)
    }

    fun reset() {
        completedConversations.clear()
    }

    companion object {
        const val SERVER_HISTORY_PAGE_SIZE = 1000
    }
}
