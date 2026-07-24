package id.homebase.api.client.notifications

import id.homebase.api.crypto.Md5
import id.homebase.api.sync.database.DatabaseManager
import kotlin.uuid.Uuid

/**
 * Local, per-device map of **outbox `uniqueId` → server `jobId`** for scheduled pushes that were
 * durably enqueued through the outbox (see [ScheduledPushOutboxUploader]).
 *
 * Why it exists: the outbox durably lands the *schedule* call, but the server-assigned [jobId] it
 * returns isn't captured anywhere in the outbox row. A later **cancel** or **update** needs that
 * jobId, so the uploader stashes it here keyed by the row's `uniqueId`, and the owning feature
 * (e.g. event reminders, keyed by `uniqueId = messageId`) reads it back to cancel/update.
 *
 * Stored in the encrypted local key/value store ([DatabaseManager.keyValue]); wiped on logout like
 * every other KV entry. Keys are derived (`Md5.toGuidId`) from the row's `uniqueId` so they live in
 * their own hashed slot and never collide with the fixed-UUID `*Preferences` namespaces.
 */
class ScheduledPushJobStore(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private fun keyFor(uniqueId: Uuid): Uuid = Md5.toGuidId("scheduledpush:job:$uniqueId")

    /** The server jobId last persisted for [uniqueId], or null if none/unparsable. */
    suspend fun get(uniqueId: Uuid): Uuid? =
        keyValue.selectByKey(keyFor(uniqueId))?.data_
            ?.let { runCatching { Uuid.parse(it.decodeToString()) }.getOrNull() }

    suspend fun put(uniqueId: Uuid, jobId: Uuid) {
        keyValue.upsertValue(keyFor(uniqueId), jobId.toString().encodeToByteArray())
    }

    suspend fun delete(uniqueId: Uuid) {
        keyValue.deleteByKey(keyFor(uniqueId))
    }
}
