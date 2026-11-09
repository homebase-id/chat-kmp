package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.RetryConfig
import id.homebase.api.client.ServerException
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.client.drives.files.wholePercentBytes
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.UploadProgress
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.isExistingFileWithUniqueId
import id.homebase.api.client.isPayloadHashRejection
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.api.client.withRetryResult
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Creates nodes the way the desktop engine does (filesync hbsync-homebase drive/mod.rs). */
class FilesyncUploader internal constructor(
    private val sendUpload: suspend (UploadFileRequest, UploadProgress?) -> Unit,
    private val fileOperationsProvider: FileOperationsProvider,
    private val clock: Clock,
) {
    constructor(
        driveUploadProvider: DriveUploadProvider,
        fileOperationsProvider: FileOperationsProvider,
        clock: Clock = Clock.System,
    ) : this({ request, progress -> driveUploadProvider.uploadFile(request, onProgress = progress) }, fileOperationsProvider, clock)

    /** The new folder's node id and key. */
    suspend fun createFolder(parentId: Uuid, name: String): Pair<Uuid, KeyHeader> {
        val nodeId = Uuid.random()
        val keyHeader = KeyHeader.newRandom16()
        create(nodeId, parentId, FilesyncProtocol.FolderFileType, FilesyncNodeContent(v = CONTENT_VERSION, name = name), keyHeader)
        return nodeId to keyHeader
    }

    suspend fun uploadFile(
        nodeId: Uuid,
        parentId: Uuid,
        sourcePath: String,
        name: String,
        modifiedMs: Long?,
        onProgress: (Float) -> Unit,
    ): KeyHeader {
        val mtime = modifiedMs ?: clock.now().toEpochMilliseconds()
        return encryptingAgainOnHashRejection(TAG) {
            val keyHeader = KeyHeader.newRandom16()
            fileOperationsProvider.withFilesyncPayload(sourcePath, keyHeader.aesKey) { payload, size ->
                val content = FilesyncNodeContent(v = CONTENT_VERSION, name = name, mtime = mtime, size = size)
                create(nodeId, parentId, FilesyncProtocol.FileFileType, content, keyHeader, payload, onProgress)
            }
            keyHeader
        }
    }

    private suspend fun create(
        nodeId: Uuid,
        parentId: Uuid,
        fileType: Int,
        content: FilesyncNodeContent,
        keyHeader: KeyHeader,
        payload: PayloadFile? = null,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        val metadata = filesyncMetadata(nodeId, parentId, fileType, OdinSystemSerializer.serialize(content), ArchivalStatus.None)
            .encryptContent(keyHeader)
        val request = UploadFileRequest(
            driveId = FilesyncProtocol.DriveAlias,
            keyHeader = keyHeader,
            metadata = metadata,
            payloads = listOfNotNull(payload),
        )

        // The node id is new to this upload and kept across every retry, the user's too: a 4105 can only mean an earlier attempt landed.
        withRetryResult(RetryConfig(maxRetries = 3, retryOn = { it.isTransientNetworkFailure() || it is ServerException }), TAG) {
            try {
                sendUpload(request, onProgress?.let(::wholePercentBytes))
            } catch (e: ClientException) {
                if (!e.isExistingFileWithUniqueId()) throw e
                Logger.i(tag = TAG) { "node $nodeId already landed on an earlier attempt" }
            }
            Unit
        }.getOrThrow()
    }

    private companion object {
        const val TAG = "FilesyncUploader"
    }
}

/** A hash rejection means the bytes changed under us or a bug: start over once from the source, never resend blindly. */
internal suspend fun <T> encryptingAgainOnHashRejection(tag: String, attempt: suspend () -> T): T =
    try {
        attempt()
    } catch (e: ClientException) {
        if (!e.isPayloadHashRejection()) throw e
        Logger.w(tag = tag) { "hash rejected (${e.errorCode}); encrypting again" }
        attempt()
    }

/** [sourcePath] as a node's encrypted, BLAKE3-hashed payload under [aesKey], for [send]; its size is the plaintext's. */
internal suspend fun <T> FileOperationsProvider.withFilesyncPayload(
    sourcePath: String,
    aesKey: SecureByteArray,
    send: suspend (payload: PayloadFile, size: Long) -> T,
): T {
    val encryptedPath = createUploadTempPath("fs_up_", ".bin")
    try {
        val hashed = encryptWithPayloadHash(sourcePath, encryptedPath, aesKey, PayloadHashAlgorithm.Blake3)
        val payload = PayloadFile(
            key = FilesyncProtocol.PayloadKey,
            filePath = encryptedPath,
            contentType = "application/octet-stream",
            isPreEncrypted = true,
            iv = hashed.iv,
            hash = hashed.hash,
        )
        return send(payload, hashed.plaintextBytes)
    } finally {
        runCatching { deleteTempFile(encryptedPath) }
    }
}

/** The whole header of a node: the server replaces it on every write and takes a missing archivalStatus for 0, so nothing is left out. */
internal fun filesyncMetadata(
    nodeId: Uuid,
    parentId: Uuid,
    fileType: Int,
    content: String,
    archivalStatus: ArchivalStatus,
    versionTag: Uuid? = null,
) = UploadFileMetadata(
    allowDistribution = false,
    isEncrypted = true,
    accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
    appData = UploadAppFileMetaData(
        uniqueId = nodeId,
        groupId = parentId,
        fileType = fileType,
        dataType = 0,
        archivalStatus = archivalStatus,
        content = content,
    ),
    versionTag = versionTag,
)
