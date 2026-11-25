package id.homebase.photobase.backup

import id.homebase.api.client.drives.FileState
import id.homebase.photobase.gallery.galleryTestFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ServerBackupDedupeTest {
    @Test
    fun theCheckAsksForTombstonesToo() {
        val ids = listOf(Uuid.random())

        val query = backedUpQuery(ids).queryParams

        assertEquals(listOf(FileState.Active, FileState.Deleted), query.fileState, "the bin's tombstones keep their uniqueId")
        assertEquals(ids, query.clientUniqueIdAtLeastOne)
    }

    @Test
    fun aLiveFileIsOnTheServerAndATombstoneIsAPhotoDeletedForGood() {
        val live = Uuid.random()
        val deleted = Uuid.random()

        val found = onServer(listOf(photo(live, FileState.Active), photo(deleted, FileState.Deleted)))

        assertEquals(OnServer(has = setOf(live), deletedForGood = setOf(deleted)), found)
    }

    private fun photo(uniqueId: Uuid, state: FileState) = galleryTestFile(0).let {
        it.copy(fileState = state, fileMetadata = it.fileMetadata.copy(appData = it.fileMetadata.appData.copy(uniqueId = uniqueId)))
    }
}
