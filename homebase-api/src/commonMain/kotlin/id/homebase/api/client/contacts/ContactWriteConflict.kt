@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.drives.ServerFile
import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Body returned with 409 from CREATE and UPDATE.
 *
 * [versionTag] is the authoritative current tag (equal to `current.fileMetadata.versionTag`); use it
 * to retry the same content delta. [current] is the same shared-secret-encrypted file-header shape
 * that drive reads return ([ServerFile]) — it also carries the current encrypted content, so conflict
 * recovery never needs a separate drive read.
 */
@Serializable
data class ContactWriteConflict(
    @Serializable(with = UuidSerializer::class) val versionTag: Uuid,
    val current: ServerFile,
) {
    /** The conflicting contact's id, read from the file header (`current.fileMetadata.appData.uniqueId`). */
    val uniqueId: Uuid
        get() = current.fileMetadata.appData.uniqueId
            ?: error("409 conflict header is missing fileMetadata.appData.uniqueId")
}
