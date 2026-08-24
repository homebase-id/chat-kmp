package id.homebase.api.client.drives.upload

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class UpdateLocalMetadataTagsOutboxRequest(
    val file: FileIdFileIdentifier,
    val versionTag: String?,
    val tags: List<String>?,
    // Stable id of the target file. Lets the uploader resolve the current (possibly
    // rekeyed) fileId at send time, so an own-send pin isn't dropped when temp→server
    // rekey moves the file out from under the enqueue-time fileId. Nullable/defaulted
    // so rows enqueued before this field existed still deserialize (fileId fallback).
    val uniqueId: Uuid? = null,
)

@Serializable
data class UpdateLocalAppdataContentOutboxRequest(
    val driveId: Uuid,
    val fileId: Uuid,
    val versionTag: String?,
    val content: String?,
    val iv: String?
)
