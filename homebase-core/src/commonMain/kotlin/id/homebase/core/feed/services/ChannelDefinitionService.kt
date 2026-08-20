package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.feedLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

// Both source drives are merged into one map keyed by the definition file's appData.uniqueId, which equals a
// post's [FeedPostItem.channelId]. [refresh] is launched in init and is safe to call again.
class ChannelDefinitionService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ChannelDefinitionService"
        private const val PageSize = 200
    }

    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private val _channels = MutableStateFlow<Map<String, ChannelDefinition>>(emptyMap())
    val channels: StateFlow<Map<String, ChannelDefinition>> = _channels.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    fun nameFor(channelId: String): String? = channels.value[channelId]?.name

    suspend fun refresh() {
        try {
            val active = credentialsManager.getActiveCredentials() ?: return
            val identityId = active.getIdentityId()

            val map = mutableMapOf<String, ChannelDefinition>()
            for (drive in setOf(feedDrive, channelDrive)) {
                val result = QueryBatch(identityId).queryBatchAsync(
                    dbm = databaseManager,
                    driveId = drive,
                    noOfItems = PageSize,
                    cursor = null,
                    sortOrder = QueryBatchSortOrder.NewestFirst,
                    sortField = QueryBatchSortField.UserDate,
                    fileSystemType = 0,
                    filetypesAnyOf = listOf(FeedProtocol.ChannelDefinitionFileType),
                )
                result.records
                    .filterNot { it.isSoftDeleted() }
                    .forEach { file ->
                        val appData = file.fileMetadata.appData
                        val id = appData.uniqueId?.toString() ?: return@forEach
                        val def = appData.content?.let { raw ->
                            runCatching { OdinSystemSerializer.deserialize<ChannelDefinition>(raw) }
                                .getOrNull()
                        } ?: return@forEach
                        map[id] = def
                    }
            }
            Logger.i(tag = TAG) { "refresh: ${map.size} channel definitions" }
            _channels.value = map
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "refresh failed: ${e.message}" }
        }
    }
}
