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

class RecordGoneException(uniqueId: Uuid) : IllegalStateException("Photobase record $uniqueId is gone")

/** An owner-only record on the Photos drive, all in its header, found by its unique id. */
class PhotobaseRecord(
    private val getFileHeaderByUid: suspend (driveId: Uuid, uniqueId: Uuid) -> HomebaseFile?,
    private val uploadFile: suspend (UploadFileRequest) -> Unit,
    private val updateFileByUniqueId: suspend (UpdateFileByUniqueIdRequest) -> Unit,
    /** The server's error for the file, or null once it's deleted. */
    private val softDelete: suspend (fileId: Uuid, versionTag: Uuid) -> OdinClientErrorCode?,
) {
    /** Null when there's none. Throws when the server can't be asked. */
    suspend fun header(uniqueId: Uuid): HomebaseFile? = getFileHeaderByUid(PhotobaseProtocol.DriveAlias, uniqueId)

    /**
     * Saves [content] of what's stored, or of null; another device's save in between asks again. Without [create],
     * a record that's gone stays gone and this throws.
     */
    suspend fun write(uniqueId: Uuid, fileType: Int, create: Boolean = true, content: (stored: String?) -> String) =
        retryingConflicts("Photobase record $uniqueId", TAG, isConflict = { it.isExistingFileWithUniqueId() || it.isVersionConflict() }) {
            val existing = header(uniqueId)
            if (!create && existing?.fileState != FileState.Active) throw RecordGoneException(uniqueId)
            val stored = existing?.fileMetadata?.appData?.content
            val merged = mergedContent(stored, Json.parseToJsonElement(content(stored)).jsonObject)
            if (existing == null) create(uniqueId, fileType, merged) else update(existing, uniqueId, fileType, merged)
        }

    /** Nothing to do for a record that's gone already. */
    suspend fun delete(uniqueId: Uuid) {
        repeat(MAX_CONFLICT_RETRIES) {
            val existing = header(uniqueId)?.takeIf { it.fileState == FileState.Active } ?: return
            val versionTag = existing.fileMetadata.versionTag ?: throw IllegalStateException("Photobase record $uniqueId has no version tag")
            when (val error = softDelete(existing.fileId, versionTag)) {
                null, OdinClientErrorCode.FileNotFound -> return
                OdinClientErrorCode.VersionTagMismatch -> Logger.i(tag = TAG) { "delete: $error, retrying" }
                else -> throw IllegalStateException("Photobase record $uniqueId not deleted: $error")
            }
        }
        throw IllegalStateException("Could not delete Photobase record $uniqueId after $MAX_CONFLICT_RETRIES conflicts")
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
                instructions = headerOnlyUpdate(),
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
    }
}

/** An update of the header alone: a new transfer IV, no payload or recipient change. */
internal fun headerOnlyUpdate() = FileUpdateInstructionSet(
    transferIv = ByteArrayUtil.getRndByteArray(16),
    locale = UpdateLocale.Local,
    recipients = emptyList(),
    manifest = UpdateManifest.build(payloads = null, toDeletePayloads = null, thumbnails = null, generatePayloadIv = false),
)

internal fun ClientException.isVersionConflict() = errorCode == OdinClientErrorCode.VersionTagMismatch

/** [attempt] again, reading afresh, while another device's save got in first; [what] names it in the failure. */
internal suspend fun <T> retryingConflicts(
    what: String,
    tag: String,
    isConflict: (ClientException) -> Boolean = { it.isVersionConflict() },
    attempt: suspend () -> T,
): T {
    repeat(MAX_CONFLICT_RETRIES) {
        try {
            return attempt()
        } catch (e: ClientException) {
            if (!isConflict(e)) throw e
            Logger.i(tag = tag) { "$what: ${e.errorCode}, retrying" }
        }
    }
    throw IllegalStateException("Could not save $what after $MAX_CONFLICT_RETRIES conflicts")
}

/**
 * [stored] JSON with [set] put in and [remove] taken out. Keeps fields this app version doesn't know, so an older phone
 * saving can't erase a newer one's.
 */
internal fun mergedContent(stored: String?, set: JsonObject, remove: Set<String> = emptySet()): String {
    val previous = stored?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }.orEmpty()
    return JsonObject(previous - remove + set).toString()
}

private const val MAX_CONFLICT_RETRIES = 5
