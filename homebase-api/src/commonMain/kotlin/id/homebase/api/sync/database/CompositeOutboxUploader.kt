package id.homebase.api.sync.database

import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.notifications.ScheduledPushOutboxUploader

/**
 * Routes each outbox row to the uploader that owns its `uploadType`: scheduled-push rows go to
 * [ScheduledPushOutboxUploader], everything else (all drive transit) to [DriveOutboxUploader].
 *
 * This is the seam that lets the scheduled-push shim ride the outbox without teaching the
 * drive-centric [DriveOutboxUploader] about pushes: [OutboxSync] holds a single [OutboxUploader],
 * so we compose the two behind one and dispatch by type. Adding a genuinely new outbox job kind is
 * "register another uploader + one more branch here", not a change to the drive path.
 */
class CompositeOutboxUploader(
    private val drive: DriveOutboxUploader,
    private val push: ScheduledPushOutboxUploader,
) : OutboxUploader {

    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        when (outboxRecord.uploadType) {
            ScheduledPushOutboxUploader.SchedulePush,
            ScheduledPushOutboxUploader.CancelPush,
            -> push.upload(outboxRecord, eventBus)

            else -> drive.upload(outboxRecord, eventBus)
        }
    }
}
