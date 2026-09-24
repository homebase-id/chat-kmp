package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import kotlin.uuid.Uuid

private const val STATUS_SCAN_PAGE_SIZE = 500

/** The sender and parsed status of a status message someone else sent us; null for anything else. */
internal fun HomebaseFile.incomingStatus(): Pair<OdinId, StatusMessageData>? {
    val appData = fileMetadata.appData
    if (appData.dataType != ChatProtocol.ChatStatusMessageDataType) return null
    // originalAuthor is null on our own synced copy, so this only fires on the receiver side.
    val sender = fileMetadata.originalAuthor ?: fileMetadata.senderOdinId ?: return null
    val content = appData.content ?: return null
    val status = runCatching {
        OdinSystemSerializer.deserialize<StatusMessageData>(content)
    }.getOrNull() ?: return null
    return sender to status
}

/** In the order given, so a designation followed by a revocation ends revoked. */
internal suspend fun dispatchStatusMessages(
    messageFiles: List<HomebaseFile>,
    onHealRequested: (suspend (status: StatusMessageData, sender: OdinId, messageFile: HomebaseFile) -> Unit)?,
    onDesignated: (suspend (sender: OdinId, messageFile: HomebaseFile) -> Unit)?,
    onRevoked: (suspend (sender: OdinId, messageFile: HomebaseFile) -> Unit)?,
) {
    for (file in messageFiles) {
        val (sender, status) = file.incomingStatus() ?: continue
        try {
            when (status.statusMessage) {
                StatusMessage.GroupHealRequested -> onHealRequested?.invoke(status, sender, file)
                StatusMessage.EmergencyContactDesignated -> onDesignated?.invoke(sender, file)
                StatusMessage.EmergencyContactRevoked -> onRevoked?.invoke(sender, file)
                else -> Unit
            }
        } catch (e: Exception) {
            Logger.e(e) {
                "ConversationStream: ${status.statusMessage} handler threw for sender=${sender.domainName}: ${e.message}"
            }
        }
    }
}

/** Status messages not yet consumed (soft-deleted), created after [createdAfter] when given, oldest first. */
internal suspend fun activeStatusMessageFiles(
    dbm: DatabaseManager,
    identityId: Uuid,
    driveId: Uuid,
    createdAfter: UnixTimeUtc? = null,
): List<HomebaseFile> {
    val files = mutableListOf<HomebaseFile>()
    var cursor: QueryBatchCursor? = createdAfter?.let(QueryBatchCursor::fromStartPoint)
    do {
        val page = QueryBatch(identityId).queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = STATUS_SCAN_PAGE_SIZE,
            cursor = cursor,
            sortOrder = QueryBatchSortOrder.OldestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            datatypesAnyOf = listOf(ChatProtocol.ChatStatusMessageDataType),
        )
        files += page.records
        cursor = page.cursor
    } while (page.hasMoreRows && page.records.isNotEmpty())
    return files
}
