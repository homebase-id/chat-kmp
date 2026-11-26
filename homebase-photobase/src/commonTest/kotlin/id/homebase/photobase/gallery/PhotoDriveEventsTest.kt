package id.homebase.photobase.gallery

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.sync.DriveState
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PhotoDriveEventsTest {
    private val photos = PhotobaseProtocol.DriveAlias

    @Test
    fun aFinishedRoundWithRecordsAndAChangeWithItsFileBothCount() = runTest {
        val arrived = galleryTestFile(1)
        val events = flowOf<BackendEvent>(
            BackendEvent.DriveEvent.Started(photos),
            BackendEvent.DriveEvent.Progress(photos, 500),
            BackendEvent.DriveEvent.Stopped(photos, 0, BackendEvent.DriveResult.Completed),
            BackendEvent.DriveEvent.Stopped(photos, 12, BackendEvent.DriveResult.Completed),
            BackendEvent.DriveEvent.Stopped(photos, 3, BackendEvent.DriveResult.Aborted("offline")),
            BackendEvent.DataEvent.BatchReceived(photos, listOf(arrived)),
            BackendEvent.DriveEvent.Stopped(Uuid.random(), 40, BackendEvent.DriveResult.Completed),
            BackendEvent.DataEvent.BatchReceived(Uuid.random(), listOf(galleryTestFile(2))),
        )

        assertEquals(
            listOf(PhotoChange.RoundFinished, PhotoChange.RoundFinished, PhotoChange.FilesArrived(listOf(arrived))),
            events.newPhotoRecords().toList(),
            "a round is silent until it stops; a failed one may still have written some",
        )
    }

    @Test
    fun aTaggedGalleryHearsOnlyArrivalsThatMayChangeATag() = runTest {
        val backedUp = galleryTestFile(1)
        val tagged = galleryTestFile(2).let { it.copy(fileMetadata = it.fileMetadata.copy(localAppData = LocalAppMetadata(versionTag = Uuid.random()))) }
        val deleted = galleryTestFile(3).copy(fileState = FileState.Deleted)
        val changes = flowOf(
            PhotoChange.FilesArrived(listOf(backedUp)),
            PhotoChange.FilesArrived(listOf(backedUp, tagged, deleted)),
            PhotoChange.RoundFinished,
        )

        assertEquals(
            listOf(PhotoChange.FilesArrived(listOf(tagged, deleted)), PhotoChange.RoundFinished),
            changes.thatMayChangeTags().toList(),
        )
        assertEquals(changes.thatMayChangeTags().toList(), changes.heardBy(PhotoGallery.Favorites).toList())
        assertEquals(changes.thatMayChangeTags().toList(), changes.heardBy(PhotoGallery.Album(Uuid.random())).toList())
        assertEquals(changes.toList(), changes.heardBy(PhotoGallery.Library).toList(), "a shelf hears a backup's new photos")
        assertEquals(changes.toList(), changes.heardBy(PhotoGallery.Archive).toList())
    }

    @Test
    fun onlyARunningSyncCountsAsSyncing() {
        assertEquals(0, DriveState.Synchronizing().syncedSoFar())
        assertEquals(1_200, DriveState.Synchronizing(1_200).syncedSoFar())
        // Mounted but not started yet, as on an offline start: not "Syncing photos (0 so far)" until a connection comes.
        assertNull(DriveState.Initialized.syncedSoFar())
        assertNull(DriveState.Completed(12).syncedSoFar())
        assertNull(DriveState.Failed("offline").syncedSoFar())
        assertNull((null as DriveState?).syncedSoFar())
    }

    @Test
    fun onlyACompletedRoundMeansTheWholeLibraryIsHere() {
        assertTrue(DriveState.Completed(0).librarySynced())
        assertFalse(DriveState.Initialized.librarySynced())
        assertFalse(DriveState.Synchronizing(10).librarySynced())
        assertFalse(DriveState.Failed("offline").librarySynced())
        assertFalse((null as DriveState?).librarySynced())
    }
}
