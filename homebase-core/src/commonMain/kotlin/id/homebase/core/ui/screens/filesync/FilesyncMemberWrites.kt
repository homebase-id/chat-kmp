package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.UploadProgress
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.files.wholePercentBytes
import id.homebase.api.client.drives.keepFileKeyHeader
import id.homebase.api.client.drives.sealKeyHeader
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where a picked file goes when it's for a folder or file another identity shares with this one. */
sealed interface FilesyncPeerTarget {
    val owner: OdinId

    /** A new file in [folder], shared like it with this identity's circles; [takenKeys] are its names' [FilesyncNames.key]s. */
    class Add(override val owner: OdinId, val folderId: Uuid, val folder: FilesyncHandle, val takenKeys: Set<String>) : FilesyncPeerTarget

    /** New contents for [node]; [sealed] when its owner's device hasn't taken it in yet, so its key header is still a seal. */
    data class Replace(override val owner: OdinId, val node: FilesyncNode, val handle: FilesyncHandle, val sealed: Boolean = false) : FilesyncPeerTarget
}

/** A write queued at this identity's server for [owner]; its result names [globalTransitId] and [sentVersionTag]. */
data class FilesyncPeerSend(
    val owner: OdinId,
    val globalTransitId: Uuid,
    /** What the upload or update gave back, which its result names. */
    val sentVersionTag: Uuid,
    val name: String,
    /** For a replace: the version it named, which a landed edit changes. */
    val namedVersionTag: Uuid? = null,
) {
    val isUpdate: Boolean get() = namedVersionTag != null
}

/**
 * A member's writes to a folder another identity shares with them, sent through this identity's server
 * (odin-core SelectiveShareTests.MemberWrites): a new file is a deposit sealed to the owner's drive key with a global
 * transit id picked here, so its shares can be locked before it exists; a replace keeps the file's key under a new IV.
 */
class FilesyncMemberWrites internal constructor(
    private val sharesOfNewFile: suspend (owner: OdinId, folder: FilesyncHandle, fileKey: KeyHeader, globalTransitId: Uuid) -> List<ShareRequest>,
    private val ownersDriveKey: suspend (OdinId) -> ByteArray,
    private val upload: suspend (UploadFileRequest, UploadProgress?) -> Uuid,
    private val update: suspend (OdinId, Uuid, EncryptedKeyHeader, UploadFileMetadata, PayloadFile, UploadProgress?) -> Uuid,
    private val fileOperationsProvider: FileOperationsProvider,
    private val clock: Clock,
) {
    constructor(
        sharing: FilesyncSharing,
        driveUploadProvider: DriveUploadProvider,
        fileOperationsProvider: FileOperationsProvider,
        clock: Clock = Clock.System,
    ) : this(
        sharesOfNewFile = sharing::sharesOfNewFileIn,
        ownersDriveKey = sharing::ownersDriveKey,
        upload = { request, progress -> requireNotNull(driveUploadProvider.uploadFile(request, onProgress = progress)).newVersionTag },
        update = { owner, gtid, keyHeader, metadata, payload, progress ->
            driveUploadProvider.updateFileOverPeerByGlobalTransitId(
                owner, FilesyncProtocol.DriveAlias, gtid, keyHeader, metadata, listOf(payload), onProgress = progress,
            ).newVersionTag
        },
        fileOperationsProvider = fileOperationsProvider,
        clock = clock,
    )

    suspend fun send(target: FilesyncPeerTarget, sourcePath: String, name: String, modifiedMs: Long?, onProgress: (Float) -> Unit): FilesyncPeerSend {
        val mtime = modifiedMs ?: clock.now().toEpochMilliseconds()
        return encryptingAgainOnHashRejection(TAG) {
            when (target) {
                is FilesyncPeerTarget.Add -> add(target, sourcePath, name, mtime, wholePercentBytes(onProgress))
                is FilesyncPeerTarget.Replace -> replace(target, sourcePath, mtime, wholePercentBytes(onProgress))
            }
        }
    }

    private suspend fun add(target: FilesyncPeerTarget.Add, sourcePath: String, name: String, mtime: Long, progress: UploadProgress): FilesyncPeerSend {
        val fileKey = KeyHeader.newRandom16()
        val globalTransitId = Uuid.random()
        // Without a share the file lands for its owner alone: nobody else in the folder would see it.
        val shares = sharesOfNewFile(target.owner, target.folder, fileKey, globalTransitId)
        check(shares.isNotEmpty()) { "${target.owner}'s folder ${target.folderId} reaches this identity through no share" }
        val sealedTo = ownersDriveKey(target.owner)
        val sent = fileOperationsProvider.withFilesyncPayload(sourcePath, fileKey.aesKey) { payload, size ->
            val content = OdinSystemSerializer.serialize(FilesyncNodeContent(v = CONTENT_VERSION, name = name, mtime = mtime, size = size))
            val request = UploadFileRequest(
                driveId = SystemDriveConstants.transientTempDrive.alias,
                keyHeader = fileKey,
                metadata = filesyncMetadata(Uuid.random(), target.folderId, FilesyncProtocol.FileFileType, content, ArchivalStatus.None)
                    .copy(allowDistribution = true)
                    .encryptContent(fileKey),
                payloads = listOf(payload),
                transitOptions = TransitOptions(
                    recipients = listOf(target.owner),
                    isTransient = true,
                    remoteTargetDrive = TargetDrive(FilesyncProtocol.DriveAlias, FilesyncProtocol.DriveType),
                ),
                sealedTo = sealedTo,
                shares = shares,
                globalTransitId = globalTransitId,
            )
            // Never sent again by itself: the outbox drops its copy once sent, so a second send would be a second file.
            upload(request, progress)
        }
        return FilesyncPeerSend(target.owner, globalTransitId, sent, name)
    }

    private suspend fun replace(target: FilesyncPeerTarget.Replace, sourcePath: String, mtime: Long, progress: UploadProgress): FilesyncPeerSend {
        val handle = target.handle
        val globalTransitId = requireNotNull(handle.globalTransitId) { "${target.node.nodeId} has no global transit id" }
        val versionTag = requireNotNull(handle.versionTag) { "${target.node.nodeId} has no version to name" }
        val fileKey = handle.keyWithNewIv()
        val keyHeader = if (target.sealed) sealKeyHeader(fileKey, ownersDriveKey(target.owner)) else keepFileKeyHeader(fileKey.iv)
        val sent = fileOperationsProvider.withFilesyncPayload(sourcePath, fileKey.aesKey) { payload, size ->
            val content = JsonObject(handle.content - REMOVAL_KEY + mapOf("mtime" to JsonPrimitive(mtime), "size" to JsonPrimitive(size)))
            // The update replaces the whole header, so it names the node again as its owner wrote it.
            val metadata = liveNodeMetadata(target.node.nodeId, handle, content, target.node.parentId, fileKey)
                .copy(allowDistribution = true, accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Connected.value))
            update(target.owner, globalTransitId, keyHeader, metadata, payload, progress)
        }
        return FilesyncPeerSend(target.owner, globalTransitId, sent, target.node.name, namedVersionTag = versionTag)
    }

    private companion object {
        const val TAG = "FilesyncMemberWrites"
    }
}
