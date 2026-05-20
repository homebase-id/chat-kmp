package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.momentsLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Local-only audience groups for moments. Modeled on `ConversationService`
 * but without admin files, distribution, or audit logging — a moments group
 * is just a saved (title, members) tuple owned by the sender.
 *
 * Storage: one file per group on the moments drive, `fileType =
 * MomentGroupFileType`, `allowDistribution = false`, no transit recipients.
 * Cold-load + event-bus pattern mirrors [MomentsFeedService].
 */
class MomentGroupService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val driveFileProvider: DriveFileProvider,
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "MomentGroupService"
    }

    private val drive = momentsLabeledDrive.drive.alias

    private val byId = mutableMapOf<Uuid, MomentGroup>()

    private val _groups = MutableStateFlow<List<MomentGroup>>(emptyList())
    val groups: StateFlow<List<MomentGroup>> = _groups.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch { coldLoad() }

        scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DataEvent || event.driveId != drive) return@collect
                if (event !is BackendEvent.DataEvent.BatchReceived) return@collect
                processIncrementalBatch(event.batchData)
            }
        }
    }

    suspend fun createGroup(title: String, members: List<OdinId>): Uuid {
        val groupId = Uuid.random()
        val keyHeader = KeyHeader.newRandom16()
        val transit = transitFor(members)

        val content = OdinSystemSerializer.serialize(
            MomentGroupContent(
                version = MomentsProtocol.MomentGroupVersionNumberOne,
                title = title,
                members = members,
            )
        )

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = transit.isNotEmpty(),
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = groupId,
                fileType = MomentsProtocol.MomentGroupFileType,
                userDate = UnixTimeUtc.now().milliseconds,
                content = content,
            ),
        )

        val request = UploadFileRequest(
            driveId = drive,
            keyHeader = keyHeader,
            metadata = unencryptedMetadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(
                recipients = transit,
                useAppNotification = false,
            ),
            payloads = emptyList(),
            thumbnails = emptyList(),
        )

        if (!outboxSync.tryEnqueue(request, priority = 1, dependencyUniqueId = null)) {
            error("Failed to enqueue group create")
        }

        try {
            optimisticWriter.writeNewFile(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
                originalRecipientCount = transit.size,
                fileSystemType = FileSystemType.Standard,
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "createGroup optimistic write failed (non-fatal)" }
        }

        return groupId
    }

    suspend fun updateGroup(groupId: Uuid, title: String, members: List<OdinId>) {
        val existing = driveFileProvider.getFileHeaderByUid(drive, groupId)
            ?: throw IllegalArgumentException("group not found: $groupId")

        val versionTag = existing.fileMetadata.versionTag
            ?: throw IllegalStateException("group $groupId missing versionTag")

        // Distribute the update to (newMembers ∪ oldMembers) so a removed
        // member receives the update too and learns they're no longer in.
        val previousMembers = existing.fileMetadata.appData.content?.let { raw ->
            runCatching {
                OdinSystemSerializer.deserialize<MomentGroupContent>(raw).members
            }.getOrNull()
        }.orEmpty()
        val transit = transitFor((members + previousMembers).distinct())

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existing.keyHeader.aesKey,
        )

        val content = OdinSystemSerializer.serialize(
            MomentGroupContent(
                version = MomentsProtocol.MomentGroupVersionNumberOne,
                title = title,
                members = members,
            )
        )

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = transit.isNotEmpty(),
            isEncrypted = true,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = groupId,
                fileType = MomentsProtocol.MomentGroupFileType,
                userDate = existing.fileMetadata.appData.userDate,
                content = content,
            ),
        )

        val manifest = UpdateManifest.build(
            payloads = null,
            toDeletePayloads = null,
            thumbnails = null,
            generatePayloadIv = false,
        )

        val request = UpdateFileByUniqueIdRequest(
            driveId = drive,
            uniqueId = groupId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = transit,
                manifest = manifest,
                useAppNotification = false,
                appNotificationOptions = null,
            ),
            metadata = unencryptedMetadata.encryptContent(keyHeader),
            payloads = emptyList(),
            thumbnails = emptyList(),
        )

        if (!outboxSync.replaceEnqueue(request, priority = 1, dependencyUniqueId = null)) {
            error("Failed to enqueue group update")
        }

        try {
            optimisticWriter.writeUpdate(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "updateGroup optimistic write failed (non-fatal)" }
        }
    }

    /**
     * Non-creator member exits the group. Two effects:
     *  1. The leaver's local copy of the group file is soft-deleted (so the
     *     group disappears from their `groups` flow).
     *  2. A small leave-notice file is distributed to the group's creator.
     *     What the creator's app does with it (auto-update the canonical
     *     members list, surface a "Bob left" UI, etc.) is a separate policy
     *     decision — the service only emits the notice.
     *
     * If the active user is the creator, this throws — the creator should
     * use [deleteGroup] (tear it down) or [updateGroup] with a member list
     * that excludes someone (remove that other member).
     */
    suspend fun leaveGroup(groupId: Uuid) {
        val existing = driveFileProvider.getFileHeaderByUid(drive, groupId)
            ?: throw IllegalArgumentException("group not found: $groupId")

        val self = credentialsManager.getActiveCredentials()?.domain
            ?: throw IllegalStateException("no active credentials")

        val creator = existing.fileMetadata.senderOdinId
            ?: throw IllegalStateException("group $groupId has no senderOdinId — cannot leave a group you created")

        if (creator == self) {
            throw IllegalStateException("creator cannot leave their own group; use deleteGroup instead")
        }

        val noticeKeyHeader = KeyHeader.newRandom16()
        // No content body — receiver resolves everything from senderOdinId
        // (who) + appData.groupId (which group) + appData.fileType (what it is).
        val noticeMetadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = Uuid.random(),
                groupId = groupId,
                fileType = MomentsProtocol.MomentGroupLeaveFileType,
                userDate = UnixTimeUtc.now().milliseconds,
                content = null,
            ),
        )
        val noticeRequest = UploadFileRequest(
            driveId = drive,
            keyHeader = noticeKeyHeader,
            metadata = noticeMetadata.encryptContent(noticeKeyHeader),
            transitOptions = TransitOptions(
                recipients = listOf(creator),
                useAppNotification = false,
            ),
            payloads = emptyList(),
            thumbnails = emptyList(),
        )
        if (!outboxSync.tryEnqueue(noticeRequest, priority = 1, dependencyUniqueId = null)) {
            error("Failed to enqueue group leave notice")
        }

        // Local-only soft-delete of the group file; the leaver's `groups`
        // flow will drop the group on the next BatchReceived (or immediately
        // via the byId update below).
        driveFileProvider.softDeleteFile(drive, existing.fileId, recipients = null)
        if (byId.remove(groupId) != null) emitSorted()
    }

    suspend fun deleteGroup(groupId: Uuid) {
        val existing = driveFileProvider.getFileHeaderByUid(drive, groupId) ?: return
        val previousMembers = existing.fileMetadata.appData.content?.let { raw ->
            runCatching {
                OdinSystemSerializer.deserialize<MomentGroupContent>(raw).members
            }.getOrNull()
        }.orEmpty()
        val transit = transitFor(previousMembers)
        driveFileProvider.softDeleteFile(
            drive,
            existing.fileId,
            recipients = transit.ifEmpty { null },
        )
        if (byId.remove(groupId) != null) emitSorted()
    }

    /**
     * Audience minus self. Members may legitimately include the active user
     * (typical for "groups I'm in" mental model); transit recipients must not.
     */
    private suspend fun transitFor(members: List<OdinId>): List<OdinId> {
        val self = credentialsManager.getActiveCredentials()?.domain ?: return emptyList()
        return members.distinct().filterNot { it == self }
    }

    private suspend fun coldLoad() {
        try {
            val active = credentialsManager.getActiveCredentials() ?: return
            val identityId = active.getIdentityId()

            val result = QueryBatch(identityId).queryBatchAsync(
                dbm = databaseManager,
                driveId = drive,
                noOfItems = 1000,
                cursor = null,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(MomentsProtocol.MomentGroupFileType),
            )

            byId.clear()
            for (file in result.records) {
                if (file.isSoftDeleted()) continue
                val item = file.toGroup() ?: continue
                byId[item.id] = item
            }
            emitSorted()
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Cold-load failed: ${e.message}" }
        }
    }

    private fun processIncrementalBatch(files: List<HomebaseFile>) {
        val groups = files.filter {
            it.fileMetadata.appData.fileType == MomentsProtocol.MomentGroupFileType
        }
        if (groups.isEmpty()) return

        var changed = false
        for (file in groups) {
            val uniqueId = file.fileMetadata.appData.uniqueId ?: continue
            if (file.isSoftDeleted()) {
                if (byId.remove(uniqueId) != null) changed = true
                continue
            }
            val item = file.toGroup() ?: continue
            byId[uniqueId] = item
            changed = true
        }
        if (changed) emitSorted()
    }

    private fun emitSorted() {
        _groups.value = byId.values.sortedBy { it.title.lowercase() }
    }
}

/**
 * UI-facing group record. `userDateMs` is the moments-drive userDate of the
 * underlying file (creation time for new groups; preserved on edit).
 */
data class MomentGroup(
    val id: Uuid,
    val title: String,
    val members: List<OdinId>,
    val userDateMs: Long,
)

private fun HomebaseFile.toGroup(): MomentGroup? {
    val appData = fileMetadata.appData
    val uniqueId = appData.uniqueId ?: return null
    val content = appData.content?.let { raw ->
        runCatching {
            OdinSystemSerializer.deserialize<MomentGroupContent>(raw)
        }.getOrNull()
    } ?: return null
    return MomentGroup(
        id = uniqueId,
        title = content.title,
        members = content.members,
        userDateMs = sqlUserDateMs(),
    )
}
