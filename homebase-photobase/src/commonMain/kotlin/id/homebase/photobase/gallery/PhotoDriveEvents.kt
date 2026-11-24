package id.homebase.photobase.gallery

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.sync.DriveState
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlin.uuid.Uuid

sealed interface PhotoChange {
    /** A sync round wrote records, anywhere in the library. */
    data object RoundFinished : PhotoChange

    /** A few files arrived with their records, after the round; their photos' dates, deleted ones too. */
    data class FilesArrived(val userDates: List<Long>) : PhotoChange
}

/**
 * When the drive sync has put new Photos records in the local index: a sync round writes without events and says so
 * once it stops, and after that each change arrives with its file.
 */
fun Flow<BackendEvent>.newPhotoRecords(photosDriveId: Uuid = PhotobaseProtocol.DriveAlias): Flow<PhotoChange> = mapNotNull { event ->
    when {
        event is BackendEvent.DriveEvent.Stopped && event.driveId == photosDriveId && event.totalCount > 0 -> PhotoChange.RoundFinished
        event is BackendEvent.DataEvent.BatchReceived && event.driveId == photosDriveId ->
            PhotoChange.FilesArrived(event.batchData.map { it.sqlUserDateMs() })
        else -> null
    }
}

/** How many records the running sync has brought in, or null when the drive isn't syncing right now. */
fun DriveState?.syncedSoFar(): Int? = (this as? DriveState.Synchronizing)?.count

/** The drive's sync has completed a round this session, so the local index holds the whole library. */
fun DriveState?.librarySynced(): Boolean = this is DriveState.Completed
