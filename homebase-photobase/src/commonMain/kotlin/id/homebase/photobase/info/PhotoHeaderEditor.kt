package id.homebase.photobase.info

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.media.MediaLocation
import id.homebase.photobase.backup.PhotoContent
import id.homebase.photobase.headerOnlyUpdate
import id.homebase.photobase.mergedContent
import id.homebase.photobase.retryingConflicts
import kotlinx.datetime.UtcOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A change to a photo's header: the original file and its EXIF stay as the camera wrote them. */
sealed interface PhotoEdit {
    /** [offset] null: the photo had no zone, and still hasn't. */
    data class Time(val at: Instant, val offset: UtcOffset?) : PhotoEdit

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
        retryingConflicts("photo $fileId") {
            val existing = header(driveId, fileId) ?: throw IllegalStateException("photo $fileId is gone")
            update(request(existing, edit))
        }
        sync()
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
            instructions = headerOnlyUpdate(),
            metadata = metadata.encryptContent(keyHeader),
        )
    }
}

/** [stored] PhotoContent with [edit] applied. A moved place drops the old altitude, which was the old place's. */
internal fun editedContent(stored: String?, edit: PhotoEdit): String {
    // A newer app's version stays, so this one can't mark its content older than it is.
    val versioned = stored?.let { runCatching { Json.parseToJsonElement(it).jsonObject.containsKey("v") }.getOrNull() } == true
    val version = if (versioned) emptyMap() else mapOf("v" to JsonPrimitive(PhotoContent.CURRENT_VERSION))
    return when (edit) {
        is PhotoEdit.Time -> {
            val set = version + ("timeByHand" to JsonPrimitive(true)) + listOfNotNull(edit.offset?.let { "offsetSeconds" to JsonPrimitive(it.totalSeconds) })
            mergedContent(stored, JsonObject(set), remove = setOf("offsetSeconds"))
        }
        is PhotoEdit.Place -> {
            val at = edit.at
            val set = buildMap {
                putAll(version)
                put("placeByHand", JsonPrimitive(true))
                if (at != null) {
                    put("latitude", JsonPrimitive(at.latitude))
                    put("longitude", JsonPrimitive(at.longitude))
                    at.altitude?.let { put("altitude", JsonPrimitive(it)) }
                }
            }
            mergedContent(stored, JsonObject(set), remove = setOf("latitude", "longitude", "altitude"))
        }
    }
}
