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

sealed interface SyncState {
    data object Idle      : SyncState  // No drives registered
    data object Syncing   : SyncState  // One or more drives are Synchronizing
    data object Completed : SyncState  // All drives Completed, none Failed/Syncing
    data object Failed    : SyncState  // One or more Failed; rest Completed/Initialized
}

fun computeSyncState(statuses: Map<Uuid, DriveStatus>): SyncState = when {
    statuses.isEmpty()                                                -> SyncState.Idle
    statuses.values.any { it.state is DriveState.Synchronizing }     -> SyncState.Syncing
    statuses.values.any { it.state is DriveState.Failed }            -> SyncState.Failed
    statuses.values.all { it.state is DriveState.Completed }         -> SyncState.Completed
    else                                                              -> SyncState.Idle
}
