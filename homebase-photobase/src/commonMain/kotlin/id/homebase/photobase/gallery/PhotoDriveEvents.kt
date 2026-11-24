package id.homebase.photobase.gallery

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.sync.DriveState
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Fires when the drive sync has put new Photos records in the local index: a sync round writes without events and
 * says so once it stops, and after that each change arrives with its file.
 */
fun Flow<BackendEvent>.newPhotoRecords(photosDriveId: Uuid = PhotobaseProtocol.DriveAlias): Flow<Unit> = filter { event ->
    (event is BackendEvent.DriveEvent.Stopped && event.driveId == photosDriveId && event.totalCount > 0) ||
        (event is BackendEvent.DataEvent.BatchReceived && event.driveId == photosDriveId)
}.map { }

/** How many records the running sync has brought in, or null when the drive isn't syncing right now. */
fun DriveState?.syncedSoFar(): Int? = (this as? DriveState.Synchronizing)?.count
