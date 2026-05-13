package id.homebase.api.client.websockets

import id.homebase.api.client.drives.TargetDrive
import kotlinx.serialization.Serializable

@Serializable
data class InboxItemReceivedNotification(
    val targetDrive: TargetDrive
)
