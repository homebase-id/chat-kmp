package id.homebase.api.sync

import id.homebase.api.common.OdinId
import kotlin.uuid.Uuid

data class DriveStatus(
    val driveId: Uuid,
    val label: String,
    val state: DriveState,
    /**
     * Owning identity of this drive when it is hosted on a **peer** (a community owner), or null
     * for the logged-in user's own drives. Lets the fail-soft logic treat remote drives differently:
     * they are excluded from the global [SyncState] aggregate and retry with exponential backoff so a
     * single offline owner can never make the whole app read "sync failed" (see DriveSyncManager).
     */
    val ownerOdinId: OdinId? = null,
) {
    /** True when this drive is hosted on a peer (owner-hosted), false for own drives. */
    val isRemote: Boolean get() = ownerOdinId != null
}

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

/**
 * Aggregate sync state for the global sync indicator. **Remote (owner-hosted) drives are excluded**
 * — a community whose owner is offline/slow must never flip the whole app to [SyncState.Failed] or
 * stall it on [SyncState.Syncing]. Per-community health is surfaced separately by reading that
 * drive's own [DriveStatus]. The aggregate therefore reflects only the user's own drives.
 */
fun computeSyncState(statuses: Map<Uuid, DriveStatus>): SyncState {
    val own = statuses.values.filterNot { it.isRemote }
    return when {
        own.isEmpty()                                       -> SyncState.Idle
        own.any { it.state is DriveState.Synchronizing }    -> SyncState.Syncing
        own.any { it.state is DriveState.Failed }           -> SyncState.Failed
        own.all { it.state is DriveState.Completed }        -> SyncState.Completed
        else                                                -> SyncState.Idle
    }
}
