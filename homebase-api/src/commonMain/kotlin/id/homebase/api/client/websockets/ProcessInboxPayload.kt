package id.homebase.api.client.websockets

import id.homebase.api.client.drives.TargetDrive
import kotlinx.serialization.Serializable

@Serializable
data class ProcessInboxPayload(
    val targetDrive: TargetDrive,
    val batchSize: Int
)