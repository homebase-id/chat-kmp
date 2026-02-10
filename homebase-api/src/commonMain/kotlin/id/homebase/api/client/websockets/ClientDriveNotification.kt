package id.homebase.api.client.websockets

import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.TargetDrive
import kotlinx.serialization.Serializable

@Serializable
data class ClientDriveNotification(
    val targetDrive: TargetDrive? = null,
    val header: ServerFile? = null,
    val previousServerFileHeader: ServerFile? = null,
    val isDeleteNotification: Boolean = false
)


