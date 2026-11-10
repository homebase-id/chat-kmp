package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.sync.database.DeviceBackupWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

enum class BackupState(val code: Long) {
    New(0),
    Hashed(1),
    Queued(2),
    Uploading(3),
    Done(4),
    Failed(5),
    Skipped(6),
    DeletedOnDevice(7),
}

data class LedgerCounts(
    val pending: Long,
    val inOutbox: Long,
    val uploading: Long,
    val done: Long,
    val failed: Long,
    /** Null while any row it covers has no known size, rather than an undercount. */
    val doneBytes: Long? = 0,
    val remainingBytes: Long? = 0,
) {
    /** Failed rows count: a foreground run retries them. */
    val workLeft: Boolean get() = pending + inOutbox + uploading + failed > 0
}

/** The one place backup state changes, and what decides whether a photo is backed up. */
class BackupLedger(
    private val store: DeviceBackupWrapper,
    private val nowMs: () -> Long,
) {
    private val _changes = MutableStateFlow(0L)

    /** Ticks on every write, so the pipeline and the UI can wait for news instead of polling. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun changed() = _changes.update { it + 1 }

    /**
     * Commits [changes] in one transaction. The caller saves the checkpoint only after this returns:
     * a crash between the two redoes the delta, and re-applying it changes nothing.
     */
    suspend fun apply(changes: DeviceChanges, onlyNewFromMs: Long?) {
        val gone = changes.deletedIds.toSet() + changes.currentIds?.let {
            store.selectAllIdsExcept(BackupState.DeletedOnDevice.code).toSet() - it
        }.orEmpty()
        val listedAssets = changes.assets
        // A full listing can be a whole library, so one read beats a read per asset.
        val existing = if (listedAssets.size > SINGLE_READ_LIMIT) {
            store.selectAll().associateBy { it.deviceAssetId }
        } else {
            listedAssets.mapNotNull { asset -> store.selectById(asset.id) }.associateBy { it.deviceAssetId }
        }
        var restarts = 0
        store.inTransaction {
            for (asset in listedAssets) {
                val row = existing[asset.id]
                val initial = if (onlyNewFromMs != null && asset.createdAtMs < onlyNewFromMs) BackupState.Skipped else BackupState.New
                val albums = asset.albumIds.joinToString(ALBUM_SEPARATOR)
                when {
                    row == null -> insertOrIgnore(
                        asset.id, albums, asset.mediaType.code, asset.createdAtMs, asset.modifiedAtMs, asset.sizeBytes ?: 0, initial.code,
                    )
                    row.state == BackupState.DeletedOnDevice.code ||
                        row.modifiedAt != asset.modifiedAtMs ||
                        (asset.sizeBytes != null && row.size != asset.sizeBytes) -> {
                        Logger.d(tag = TAG) { "restart ${asset.id}: modifiedAt ${row.modifiedAt}->${asset.modifiedAtMs} size ${row.size}->${asset.sizeBytes}" }
                        restarts++
                        restart(albums, asset.mediaType.code, asset.createdAtMs, asset.modifiedAtMs, asset.sizeBytes ?: 0, initial.code, asset.id)
                    }
                    row.albumIds != albums -> updateAlbums(albums, asset.id)
                }
            }
            for (id in gone - listedAssets.map { it.id }.toSet()) {
                setState(BackupState.DeletedOnDevice.code, null, 0, 0, id)
            }
        }
        if (restarts > 0) Logger.i(tag = TAG) { "restarted $restarts of ${listedAssets.size} listed" }
        changed()
    }

    /** Empty when the stored one can't be trusted for this scope, which makes the source list everything. */
    suspend fun deviceCheckpoint(albumIds: Set<String>, forceFullScan: Boolean): Map<String, String> {
        val stored = store.checkpoints()
        if (forceFullScan || stored[SCOPE_KEY].orEmpty() != scopeOf(albumIds)) return emptyMap()
        return stored.filterKeys { !it.startsWith(OWN_KEY_PREFIX) }
    }

    /** Call only after [apply] returned. */
    suspend fun saveDeviceCheckpoint(values: Map<String, String>, albumIds: Set<String>) {
        store.saveCheckpoints(values + (SCOPE_KEY to scopeOf(albumIds)))
    }

    private fun scopeOf(albumIds: Set<String>) = albumIds.sorted().joinToString(ALBUM_SEPARATOR)

    suspend fun isInitialBackupDone(): Boolean = store.checkpoints()[INITIAL_DONE_KEY] != null

    suspend fun markInitialBackupDone() = store.saveCheckpoints(mapOf(INITIAL_DONE_KEY to "1"))

    suspend fun lastSuccessMs(): Long? = store.checkpoints()[LAST_SUCCESS_KEY]?.toLongOrNull()

    suspend fun markSucceeded() = store.saveCheckpoints(mapOf(LAST_SUCCESS_KEY to nowMs().toString()))

    /** Persisted: the process can die between a background run and the next app open. */
    suspend fun markWokenInBackground() = store.saveCheckpoints(mapOf(WOKEN_IN_BACKGROUND_KEY to "1"))

    suspend fun takeWokenInBackground(): Boolean {
        val woken = store.checkpoints()[WOKEN_IN_BACKGROUND_KEY] == "1"
        if (woken) store.saveCheckpoints(mapOf(WOKEN_IN_BACKGROUND_KEY to "0"))
        return woken
    }

    suspend fun markRan() = store.saveCheckpoints(mapOf(LAST_RAN_KEY to nowMs().toString()))

    /** True at most once per [gap], across process deaths: iOS often starts a fresh process for each push. */
    suspend fun takeOtherPushWake(gap: Duration): Boolean {
        val now = nowMs()
        val last = store.checkpoints()[LAST_OTHER_PUSH_WAKE_KEY]?.toLongOrNull()
        if (last != null && now - last < gap.inWholeMilliseconds) return false
        store.saveCheckpoints(mapOf(LAST_OTHER_PUSH_WAKE_KEY to now.toString()))
        return true
    }

    /** At most once per [STUCK_AFTER]. The first answer only starts the clock, for a backup that has never run. */
    suspend fun takeStuckAlert(): Boolean {
        val stored = store.checkpoints()
        val now = nowMs()
        val lastRan = stored[LAST_RAN_KEY]?.toLongOrNull()
        if (lastRan == null) {
            markRan()
            return false
        }
        val lastAlert = stored[LAST_STUCK_ALERT_KEY]?.toLongOrNull() ?: 0
        val stuck = STUCK_AFTER.inWholeMilliseconds
        if (now - lastRan < stuck || now - lastAlert < stuck) return false
        store.saveCheckpoints(mapOf(LAST_STUCK_ALERT_KEY to now.toString()))
        return true
    }

    suspend fun due(state: BackupState, includeVideos: Boolean, limit: Int, newestFirst: Boolean): List<DeviceBackupAsset> =
        store.selectDue(state.code, nowMs(), includeVideos, limit.toLong(), newestFirst)

    suspend fun row(id: String): DeviceBackupAsset? = store.selectById(id)

    suspend fun markHashed(id: String, contentHash: String, uniqueId: Uuid, sizeBytes: Long) {
        store.markHashed(id, contentHash, uniqueId.toString(), BackupState.Hashed.code, sizeBytes)
        changed()
    }

    suspend fun markDone(id: String) {
        store.setState(id, BackupState.Done.code, null, 0, 0)
        changed()
    }

    suspend fun markQueued(id: String) {
        store.setState(id, BackupState.Queued.code, null, row(id)?.attempts ?: 0, 0)
        changed()
    }

    suspend fun markSkipped(id: String, reason: String) {
        store.setState(id, BackupState.Skipped.code, reason, 0, 0)
        changed()
    }

    /** The bytes changed between hashing and encrypting: start over, never upload new bytes under the old id. */
    suspend fun markChangedUnderUs(id: String) {
        store.setState(id, BackupState.New.code, "changed while backing up", 0, 0)
        changed()
    }

    /** Keeps the row where it is, backed off. After too many attempts it's FAILED, until a foreground run or the user retries it. */
    suspend fun recordFailure(row: DeviceBackupAsset, reason: String) {
        val attempts = row.attempts + 1
        val state = if (attempts >= MAX_ATTEMPTS) BackupState.Failed.code else row.state
        store.setState(row.deviceAssetId, state, reason, attempts, nowMs() + backoff(attempts).inWholeMilliseconds)
        changed()
    }

    /** Counts no attempt. Backed off so these photos can't use up every short window ahead of ones on the phone. */
    suspend fun deferUntilOnDevice(row: DeviceBackupAsset) {
        store.setState(row.deviceAssetId, row.state, NOT_ON_DEVICE, row.attempts, nowMs() + NOT_ON_DEVICE_RETRY.inWholeMilliseconds)
        changed()
    }

    /** Counts no attempt: the video was fine, and something a person sent went first. */
    suspend fun deferForOtherVideoWork(row: DeviceBackupAsset) {
        store.setState(row.deviceAssetId, row.state, row.lastError, row.attempts, nowMs() + VIDEO_YIELD_RETRY.inWholeMilliseconds)
        changed()
    }

    /** Counts no attempt: an unreachable server says nothing about the photos. */
    suspend fun deferForOutage(rows: List<DeviceBackupAsset>, reason: String) {
        val retryAt = nowMs() + OUTAGE_RETRY.inWholeMilliseconds
        for (row in rows) store.setState(row.deviceAssetId, row.state, reason, row.attempts, retryAt)
        changed()
    }

    suspend fun clearNotOnDeviceDeferrals() = store.clearBackoffForReason(NOT_ON_DEVICE)

    suspend fun markSending(uniqueId: Uuid, taskId: Long) {
        store.setUploadTask(uniqueId.toString(), BackupState.Uploading.code, taskId)
        changed()
    }

    suspend fun onOutboxStarted(uniqueId: Uuid) {
        store.setStateOfUnique(uniqueId.toString(), listOf(BackupState.Queued.code), BackupState.Uploading.code, null, 0, 0)
        changed()
    }

    suspend fun onOutboxCompleted(uniqueId: Uuid) {
        store.selectByUnique(uniqueId.toString()).forEach {
            Logger.i(tag = "BackupLag") { "lagMs=${nowMs() - it.createdAt} mediaType=${it.mediaType}" }
        }
        store.setStateOfUnique(uniqueId.toString(), IN_OUTBOX, BackupState.Done.code, null, 0, 0)
        changed()
    }

    /** Until the Outbox starts it again on its own backoff, the row takes no send slot. */
    suspend fun onOutboxRetrying(uniqueId: Uuid) {
        store.setStateOfUnique(uniqueId.toString(), IN_OUTBOX, BackupState.Queued.code, OUTBOX_RETRYING, 0, 0)
        changed()
    }

    /** A drop never loses a photo: back to HASHED with a backoff, or FAILED once its attempts are used up. */
    suspend fun onOutboxDropped(uniqueId: Uuid, reason: String, countsAttempt: Boolean = true) {
        val added = if (countsAttempt) 1L else 0L
        val attempts = (store.selectByUnique(uniqueId.toString()).maxOfOrNull { it.attempts } ?: 0) + added
        val state = if (attempts >= MAX_ATTEMPTS) BackupState.Failed else BackupState.Hashed
        store.setStateOfUnique(
            uniqueId.toString(),
            IN_OUTBOX,
            state.code,
            reason,
            nowMs() + backoff(attempts).inWholeMilliseconds,
            added,
        )
        changed()
    }

    suspend fun rowsInOutbox(): List<DeviceBackupAsset> = store.selectInStates(IN_OUTBOX)

    suspend fun requeueFailed() {
        store.requeueFailed(BackupState.Failed.code, BackupState.New.code, BackupState.Hashed.code)
        changed()
    }

    /** Done is only what the phone remembers: this sends every Done row back through the server check. */
    suspend fun recheckDone() {
        store.recheckDone(BackupState.Done.code, BackupState.New.code, BackupState.Hashed.code)
        changed()
    }

    suspend fun handedOffCount(): Long = store.countInStates(listOf(BackupState.Queued.code, BackupState.Uploading.code))

    suspend fun sendingCount(): Long = store.countSending(IN_OUTBOX, OUTBOX_RETRYING)

    suspend fun counts(): LedgerCounts {
        val byState = store.countByState()
        fun count(state: BackupState) = byState[state.code] ?: 0
        val bytes = store.bytesByState()
        val unknown = store.unknownSizeByState()
        fun bytes(states: List<BackupState>): Long? =
            if (states.any { (unknown[it.code] ?: 0) > 0 }) null else states.sumOf { bytes[it.code] ?: 0 }
        return LedgerCounts(
            pending = count(BackupState.New) + count(BackupState.Hashed),
            inOutbox = count(BackupState.Queued),
            uploading = count(BackupState.Uploading),
            done = count(BackupState.Done),
            failed = count(BackupState.Failed),
            doneBytes = bytes(listOf(BackupState.Done)),
            remainingBytes = bytes(listOf(BackupState.New, BackupState.Hashed, BackupState.Queued, BackupState.Uploading)),
        )
    }

    suspend fun failures(limit: Int): List<BackupFailure> =
        store.selectFailures(BackupState.Failed.code, limit.toLong()).map { BackupFailure(it.deviceAssetId, it.lastError.orEmpty()) }

    companion object {
        private const val ALBUM_SEPARATOR = "\n"
        private const val SINGLE_READ_LIMIT = 500
        const val MAX_ATTEMPTS = 10
        private const val NOT_ON_DEVICE = "not on this device"
        private val NOT_ON_DEVICE_RETRY = 6.hours
        private val VIDEO_YIELD_RETRY = 1.minutes
        private val OUTAGE_RETRY = 5.minutes
        private const val OUTBOX_RETRYING = "waiting for the Outbox to retry"
        private const val OWN_KEY_PREFIX = "photobase."
        private const val INITIAL_DONE_KEY = "${OWN_KEY_PREFIX}initialBackupDone"
        private const val SCOPE_KEY = "${OWN_KEY_PREFIX}albumScope"
        private const val LAST_SUCCESS_KEY = "${OWN_KEY_PREFIX}lastSuccessMs"
        private const val LAST_RAN_KEY = "${OWN_KEY_PREFIX}lastRanMs"
        private const val LAST_OTHER_PUSH_WAKE_KEY = "${OWN_KEY_PREFIX}lastOtherPushWakeMs"
        private const val LAST_STUCK_ALERT_KEY = "${OWN_KEY_PREFIX}lastStuckAlertMs"
        private const val WOKEN_IN_BACKGROUND_KEY = "${OWN_KEY_PREFIX}wokenInBackground"
        private const val TAG = "BackupLedger"
        val STUCK_AFTER: Duration = 24.hours
        private val FIRST_RETRY: Duration = 1.minutes
        private val LONGEST_RETRY: Duration = 6.hours
        private val IN_OUTBOX = listOf(BackupState.Queued.code, BackupState.Uploading.code)

        fun backoff(attempts: Long): Duration {
            val doublings = (attempts - 1).coerceIn(0, 20).toInt()
            return (FIRST_RETRY * (1L shl doublings).toDouble()).coerceAtMost(LONGEST_RETRY)
        }
    }
}
