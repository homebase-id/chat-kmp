package id.homebase.photobase.backup

import id.homebase.api.sync.database.DeviceBackupAsset
import kotlin.uuid.Uuid

sealed interface HandOffResult {
    data object Queued : HandOffResult

    /** Already with something that outlives this process, as task [taskId]. */
    data class Sending(val taskId: Long) : HandOffResult

    /** The bytes on the device no longer match the hash; nothing was queued. */
    data object ChangedUnderUs : HandOffResult
}

/** How many rows may wait on the Outbox at once: each is an encrypted copy on disk. */
const val MAX_OUTBOX_HANDED_OFF = 6

/**
 * Takes a HASHED asset to where it will be sent without more work from the run: the Outbox, or the
 * iOS background URLSession. Expected failures throw; the run records them against the row.
 */
interface BackupUploader {
    /** True when a result can arrive long after this run ends, so a run in the background doesn't wait for it. */
    val outlivesProcess: Boolean get() = false

    /** How many rows may be with this uploader unsettled at once. */
    val maxHandedOff: Int get() = MAX_OUTBOX_HANDED_OFF

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
