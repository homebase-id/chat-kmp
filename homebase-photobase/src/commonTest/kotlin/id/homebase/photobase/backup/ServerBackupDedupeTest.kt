package id.homebase.photobase.backup

import id.homebase.api.client.drives.FileState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ServerBackupDedupeTest {
    @Test
    fun aPhotoDeletedForGoodFromTheBinCountsAsBackedUp() {
        val ids = listOf(Uuid.random())

        val query = backedUpQuery(ids).queryParams

        assertEquals(listOf(FileState.Active, FileState.Deleted), query.fileState, "the bin's tombstones keep their uniqueId")
        assertEquals(ids, query.clientUniqueIdAtLeastOne)
    }
}
