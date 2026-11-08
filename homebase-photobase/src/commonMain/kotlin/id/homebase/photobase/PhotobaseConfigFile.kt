package id.homebase.photobase

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.isExistingFileWithUniqueId
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/** Settings every device must agree on. New fields must be nullable: files without them already exist. */
@Serializable
data class PhotobaseConfig(
    val version: Int = 1,
    val pushIntervalMs: Long? = null,
)

class PhotobaseConfigFile(
    private val getFileHeaderByUid: suspend (driveId: Uuid, uniqueId: Uuid) -> HomebaseFile?,
    private val uploadFile: suspend (UploadFileRequest) -> Unit,
    private val updateFileByUniqueId: suspend (UpdateFileByUniqueIdRequest) -> Unit,
) {
    /** Null when no device has saved one yet. Throws when the server can't be asked. */
    suspend fun read(): PhotobaseConfig? = header()?.let { parse(it.fileMetadata.appData.content) }

    suspend fun update(transform: (PhotobaseConfig) -> PhotobaseConfig) {
        repeat(MAX_CONFLICT_RETRIES) {
            val existing = header()
            val stored = existing?.fileMetadata?.appData?.content
            val content = merged(stored, transform(parse(stored) ?: PhotobaseConfig()))
            try {
                if (existing == null) create(content) else update(existing, content)
                return
            } catch (e: ClientException) {
                val conflict = e.isExistingFileWithUniqueId() || e.errorCode == OdinClientErrorCode.VersionTagMismatch
                if (!conflict) throw e
                Logger.i(tag = TAG) { "update: ${e.errorCode}, retrying" }
            }
        }
        throw IllegalStateException("Could not save the Photobase config after $MAX_CONFLICT_RETRIES conflicts")
    }

    private suspend fun header() = getFileHeaderByUid(PhotobaseProtocol.DriveAlias, PhotobaseProtocol.ConfigUniqueId)

    private fun parse(content: String?): PhotobaseConfig? {
        if (content == null) return null
        return try {
            OdinSystemSerializer.deserialize<PhotobaseConfig>(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "unreadable config file" }
            null
        }
    }

    // Keeps fields this app version doesn't know, so an older phone saving can't erase a newer one's.
    private fun merged(stored: String?, config: PhotobaseConfig): String {
        val known = OdinSystemSerializer.serialize(config)
        val previous = stored?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return known
        return JsonObject(previous + Json.parseToJsonElement(known).jsonObject).toString()
    }

    private suspend fun create(content: String) {
        val keyHeader = KeyHeader.newRandom16()
        uploadFile(
            UploadFileRequest(
                driveId = PhotobaseProtocol.DriveAlias,
                keyHeader = keyHeader,
                metadata = metadata(content, encrypted = true, versionTag = null).encryptContent(keyHeader),
            ),
        )
    }

    private suspend fun update(existing: HomebaseFile, content: String) {
        val keyHeader = KeyHeader(iv = ByteArrayUtil.getRndByteArray(16), aesKey = existing.keyHeader.aesKey)
        updateFileByUniqueId(
            UpdateFileByUniqueIdRequest(
                driveId = PhotobaseProtocol.DriveAlias,
                uniqueId = PhotobaseProtocol.ConfigUniqueId,
                keyHeader = keyHeader,
                instructions = FileUpdateInstructionSet(
                    transferIv = ByteArrayUtil.getRndByteArray(16),
                    locale = UpdateLocale.Local,
                    recipients = emptyList(),
                    manifest = UpdateManifest.build(
                        payloads = null,
                        toDeletePayloads = null,
                        thumbnails = null,
                        generatePayloadIv = false,
                    ),
                ),
                metadata = metadata(content, existing.serverFileIsEncrypted, existing.fileMetadata.versionTag)
                    .encryptContent(keyHeader),
            ),
        )
    }

    private fun metadata(content: String, encrypted: Boolean, versionTag: Uuid?) = UploadFileMetadata(
        allowDistribution = false,
        isEncrypted = encrypted,
        versionTag = versionTag,
        // The drive is shared by circle, and a circle member has no business reading this.
        accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
        appData = UploadAppFileMetaData(
            uniqueId = PhotobaseProtocol.ConfigUniqueId,
            fileType = PhotobaseProtocol.ConfigFileType,
            content = content,
        ),
    )

    private companion object {
        const val TAG = "PhotobaseConfigFile"
        const val MAX_CONFLICT_RETRIES = 5
    }
}
