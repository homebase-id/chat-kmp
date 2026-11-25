package id.homebase.photobase

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.isExistingFileWithUniqueId
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
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

class PhotobaseConfigFile(private val record: PhotobaseRecord) {

    /** Null when no device has saved one yet. Throws when the server can't be asked. */
    suspend fun read(): PhotobaseConfig? = parseRecord(record.header(PhotobaseProtocol.ConfigUniqueId)?.fileMetadata?.appData?.content, "config file")

    suspend fun update(transform: (PhotobaseConfig) -> PhotobaseConfig) =
        record.write(PhotobaseProtocol.ConfigUniqueId, PhotobaseProtocol.ConfigFileType) { stored ->
            OdinSystemSerializer.serialize(transform(parseRecord(stored, "config file") ?: PhotobaseConfig()))
        }
}

/** Null for no content, or content that won't parse. */
inline fun <reified T> parseRecord(content: String?, what: String): T? {
    if (content == null) return null
    return try {
        OdinSystemSerializer.deserialize<T>(content)
    } catch (e: Exception) {
        Logger.w(tag = "PhotobaseRecord", throwable = e) { "unreadable $what" }
        null
    }
}

/** An owner-only record on the Photos drive, all in its header, found by its unique id. */
class PhotobaseRecord(
    private val getFileHeaderByUid: suspend (driveId: Uuid, uniqueId: Uuid) -> HomebaseFile?,
    private val uploadFile: suspend (UploadFileRequest) -> Unit,
    private val updateFileByUniqueId: suspend (UpdateFileByUniqueIdRequest) -> Unit,
    /** The server's error for the file, or null once it's deleted. */
    private val softDelete: suspend (fileId: Uuid, versionTag: Uuid) -> OdinClientErrorCode?,
) {
    /** Null when there's none, or it was deleted. Throws when the server can't be asked. */
    suspend fun header(uniqueId: Uuid): HomebaseFile? =
        getFileHeaderByUid(PhotobaseProtocol.DriveAlias, uniqueId)?.takeIf { it.fileState == FileState.Active }

    /**
     * Saves [content] of what's stored, or of null; another device's save in between asks again. Without [create],
     * a record that's gone stays gone and this throws.
     */
    suspend fun write(uniqueId: Uuid, fileType: Int, create: Boolean = true, content: (stored: String?) -> String) {
        repeat(MAX_CONFLICT_RETRIES) {
            val existing = header(uniqueId)
            if (existing == null && !create) throw IllegalStateException("Photobase record $uniqueId is gone")
            val stored = existing?.fileMetadata?.appData?.content
            val merged = merged(stored, content(stored))
            try {
                if (existing == null) create(uniqueId, fileType, merged) else update(existing, uniqueId, fileType, merged)
                return
            } catch (e: ClientException) {
                val conflict = e.isExistingFileWithUniqueId() || e.errorCode == OdinClientErrorCode.VersionTagMismatch
                if (!conflict) throw e
                Logger.i(tag = TAG) { "write: ${e.errorCode}, retrying" }
            }
        }
        throw IllegalStateException("Could not save Photobase record $uniqueId after $MAX_CONFLICT_RETRIES conflicts")
    }

    /** Nothing to do for a record that's gone already. */
    suspend fun delete(uniqueId: Uuid) {
        val existing = header(uniqueId) ?: return
        val versionTag = existing.fileMetadata.versionTag ?: throw IllegalStateException("Photobase record $uniqueId has no version tag")
        val error = softDelete(existing.fileId, versionTag)
        if (error != null && error != OdinClientErrorCode.FileNotFound) throw IllegalStateException("Photobase record $uniqueId not deleted: $error")
    }

    // Keeps fields this app version doesn't know, so an older phone saving can't erase a newer one's.
    private fun merged(stored: String?, known: String): String {
        val previous = stored?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return known
        return JsonObject(previous + Json.parseToJsonElement(known).jsonObject).toString()
    }

    private suspend fun create(uniqueId: Uuid, fileType: Int, content: String) {
        val keyHeader = KeyHeader.newRandom16()
        uploadFile(
            UploadFileRequest(
                driveId = PhotobaseProtocol.DriveAlias,
                keyHeader = keyHeader,
                metadata = metadata(uniqueId, fileType, content, encrypted = true, versionTag = null).encryptContent(keyHeader),
            ),
        )
    }

    private suspend fun update(existing: HomebaseFile, uniqueId: Uuid, fileType: Int, content: String) {
        val keyHeader = existing.keyHeader.withNewIv()
        updateFileByUniqueId(
            UpdateFileByUniqueIdRequest(
                driveId = PhotobaseProtocol.DriveAlias,
                uniqueId = uniqueId,
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
                metadata = metadata(uniqueId, fileType, content, existing.serverFileIsEncrypted, existing.fileMetadata.versionTag)
                    .encryptContent(keyHeader),
            ),
        )
    }

    private fun metadata(uniqueId: Uuid, fileType: Int, content: String, encrypted: Boolean, versionTag: Uuid?) = UploadFileMetadata(
        allowDistribution = false,
        isEncrypted = encrypted,
        versionTag = versionTag,
        // The drive is shared by circle, and a circle member has no business reading this.
        accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
        appData = UploadAppFileMetaData(
            uniqueId = uniqueId,
            fileType = fileType,
            content = content,
        ),
    )

    private companion object {
        const val TAG = "PhotobaseRecord"
        const val MAX_CONFLICT_RETRIES = 5
    }
}
