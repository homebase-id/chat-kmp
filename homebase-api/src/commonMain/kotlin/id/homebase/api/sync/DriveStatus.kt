package id.homebase.api.sync

import kotlin.uuid.Uuid

data class DriveStatus(
    val driveId: Uuid,
    val label: String,
    val state: DriveState,
)

sealed interface DriveState {
    data object Initialized                          : DriveState
    data class  Synchronizing(val count: Int = 0)   : DriveState
    data class  Completed(val totalCount: Int = 0)  : DriveState
    data class  Failed(val message: String)          : DriveState
}
