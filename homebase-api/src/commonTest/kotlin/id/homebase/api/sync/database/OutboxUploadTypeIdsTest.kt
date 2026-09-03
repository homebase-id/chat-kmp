package id.homebase.api.sync.database

import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.notifications.ScheduledPushOutboxUploader
import kotlin.test.Test
import kotlin.test.assertEquals

/** CompositeOutboxUploader routes on these ids, so a collision silently sends a row to the wrong uploader. */
class OutboxUploadTypeIdsTest {

    @Test
    fun drive_and_push_upload_type_ids_do_not_overlap() {
        val drive = listOf(
            DriveOutboxUploader.UploadNewFile,
            DriveOutboxUploader.UpdateFile,
            DriveOutboxUploader.DeleteFile,
            DriveOutboxUploader.UpdateLocalMetadataTags,
            DriveOutboxUploader.UpdateLocalMetadataContent,
            DriveOutboxUploader.SendReadReceiptByFileIds,
            DriveOutboxUploader.ToggleReaction,
            DriveOutboxUploader.DeleteFilesByGroupId,
            DriveOutboxUploader.SetReactions,
        )
        val push = listOf(
            ScheduledPushOutboxUploader.SchedulePush,
            ScheduledPushOutboxUploader.CancelPush,
        )
        val all = drive + push
        assertEquals(all.size, all.toSet().size, "duplicate outbox uploadType id in $all")
    }
}
