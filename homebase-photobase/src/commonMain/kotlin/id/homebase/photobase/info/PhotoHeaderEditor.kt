package id.homebase.photobase.info

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.media.MediaLocation
import id.homebase.photobase.backup.PhotoContent
import kotlinx.datetime.UtcOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A change to a photo's header: the original file and its EXIF stay as the camera wrote them. */
sealed interface PhotoEdit {
    /** Sets `userDate` to [at], taken at [offset]. */
    data class Time(val at: Instant, val offset: UtcOffset) : PhotoEdit

    /** Null removes it. */
    data class Place(val at: MediaLocation?) : PhotoEdit
}

/** Writes a [PhotoEdit] to the photo's header on the drive, keeping the rest of the header as it is. */
class PhotoHeaderEditor(
    private val header: suspend (driveId: Uuid, fileId: Uuid) -> HomebaseFile?,
    private val update: suspend (UpdateFileByFileIdRequest) -> Unit,
    /** Brings the change back into the local index, so the gallery moves the photo to its new day. */
    private val sync: () -> Unit,
) {
    suspend fun save(driveId: Uuid, fileId: Uuid, edit: PhotoEdit) {
        repeat(MAX_CONFLICT_RETRIES) {
            val existing = header(driveId, fileId) ?: throw IllegalStateException("photo $fileId is gone")
            try {
                update(request(existing, edit))
                sync()
                return
            } catch (e: ClientException) {
                if (e.errorCode != OdinClientErrorCode.VersionTagMismatch) throw e
                Logger.i(tag = TAG) { "save: ${e.errorCode}, retrying" }
            }
        }
        throw IllegalStateException("Could not save photo $fileId after $MAX_CONFLICT_RETRIES conflicts")
    }

    private suspend fun request(existing: HomebaseFile, edit: PhotoEdit): UpdateFileByFileIdRequest {
        val appData = existing.fileMetadata.appData
        val keyHeader = existing.keyHeader.withNewIv()
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = existing.serverFileIsEncrypted,
            accessControlList = existing.serverMetadata.accessControlList,
            versionTag = existing.fileMetadata.versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = appData.uniqueId,
                tags = appData.tags,
                fileType = appData.fileType,
                dataType = appData.dataType,
                userDate = if (edit is PhotoEdit.Time) edit.at.toEpochMilliseconds() else appData.userDate,
                groupId = appData.groupId,
                archivalStatus = appData.archivalStatus,
                content = editedContent(appData.content, edit),
                previewThumbnail = appData.previewThumbnail,
            ),
        )
        return UpdateFileByFileIdRequest(
            driveId = existing.driveId,
            fileId = existing.fileId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = emptyList(),
                manifest = UpdateManifest.build(payloads = null, toDeletePayloads = null, thumbnails = null, generatePayloadIv = false),
            ),
            metadata = metadata.encryptContent(keyHeader),
        )
    }

    private companion object {
        const val TAG = "PhotoHeaderEditor"
        const val MAX_CONFLICT_RETRIES = 5
    }
}

/** [stored] PhotoContent with [edit] applied; fields this version doesn't know are kept, so a newer app's survive. */
internal fun editedContent(stored: String?, edit: PhotoEdit): String {
    val fields = stored?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }.orEmpty().toMutableMap()
    fields.getOrPut("v") { JsonPrimitive(PhotoContent.CURRENT_VERSION) }
    when (edit) {
        is PhotoEdit.Time -> fields["offsetSeconds"] = JsonPrimitive(edit.offset.totalSeconds)
        is PhotoEdit.Place -> {
            fields.remove("altitude")
            val at = edit.at
            if (at == null) {
                fields.remove("latitude")
                fields.remove("longitude")
            } else {
                fields["latitude"] = JsonPrimitive(at.latitude)
                fields["longitude"] = JsonPrimitive(at.longitude)
                at.altitude?.let { fields["altitude"] = JsonPrimitive(it) }
            }
        }
    }
    return JsonObject(fields).toString()
}
