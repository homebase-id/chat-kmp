package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.TargetDrive
import kotlinx.serialization.Serializable

/** File identifier using fileId for local operations. */
@Serializable
data class FileIdFileIdentifier(
    val fileId: String,
    val targetDrive: TargetDrive
)