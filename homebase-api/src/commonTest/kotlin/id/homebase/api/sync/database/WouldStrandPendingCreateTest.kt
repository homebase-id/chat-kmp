package id.homebase.api.sync.database

import id.homebase.api.client.drives.files.DriveOutboxUploader
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The outbox guard predicate: an UpdateFile must never silently replace a
 * pending UploadNewFile (they share (driveId, uniqueId), so the replace would
 * delete the un-sent create and the update would then fail "Could not find
 * file" — losing the message). See [wouldStrandPendingCreate].
 */
class WouldStrandPendingCreateTest {

    private val create = DriveOutboxUploader.UploadNewFile
    private val update = DriveOutboxUploader.UpdateFile
    private val delete = DriveOutboxUploader.DeleteFile

    @Test
    fun update_over_pending_create_isStranding() {
        assertTrue(wouldStrandPendingCreate(existingUploadType = create, incomingUploadType = update))
    }

    @Test
    fun create_over_pending_create_isFine() {
        // Coalescing an edit re-enqueues AS a create — must be allowed.
        assertFalse(wouldStrandPendingCreate(existingUploadType = create, incomingUploadType = create))
    }

    @Test
    fun update_over_pending_update_isFine() {
        // The file already exists on the server; replacing one edit with another is fine.
        assertFalse(wouldStrandPendingCreate(existingUploadType = update, incomingUploadType = update))
    }

    @Test
    fun update_with_no_pending_row_isFine() {
        assertFalse(wouldStrandPendingCreate(existingUploadType = null, incomingUploadType = update))
    }

    @Test
    fun nonUpdate_incoming_isNeverStranding() {
        // Only an incoming UpdateFile can strand a create; deletes etc. don't.
        assertFalse(wouldStrandPendingCreate(existingUploadType = create, incomingUploadType = delete))
        assertFalse(wouldStrandPendingCreate(existingUploadType = create, incomingUploadType = create))
    }
}
