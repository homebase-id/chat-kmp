package id.homebase.photobase.backup

import id.homebase.api.sync.database.DeviceBackupAsset
import kotlin.uuid.Uuid

sealed interface HandOffResult {
    data object Queued : HandOffResult

    /** The bytes on the device no longer match the hash; nothing was queued. */
    data object ChangedUnderUs : HandOffResult
}

/**
 * Takes a HASHED asset to where it will be sent without more work from the run: the Outbox, or the
 * iOS background URLSession. Expected failures throw; the run records them against the row.
 */
interface BackupUploader {
    suspend fun handOff(row: DeviceBackupAsset, uniqueId: Uuid): HandOffResult

    /** Whether something is still going to send this row. False sends the row back through dedupe. */
    suspend fun isStillSending(row: DeviceBackupAsset, uniqueId: Uuid): Boolean

    /** Start whatever handed-off work isn't moving yet. */
    suspend fun kick()
}

/** Which of [uniqueIds] are already on the Photos drive. */
fun interface BackupDedupe {
    suspend fun existing(uniqueIds: List<Uuid>): Set<Uuid>
}

/** Waits out a cold background launch's credential restore; false means there's no signed-in user. */
fun interface BackupAuth {
    suspend fun awaitSignedIn(): Boolean
}

/** Arms and cancels the platform's triggers. A run re-arms them when it starts and when it ends. */
fun interface BackupScheduler {
    fun rearm(settings: BackupSettings)
}
