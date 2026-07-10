package id.homebase.api.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.coroutines.supervisedScope
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid

/**
 * Receives decrypted [HomebaseFile] headers shipped on the WebSocket
 * push channel and writes them straight to [DriveMainIndex] without
 * going through HTTP `Drive.sync()`.
 *
 * One worker instance per drive. [OdinWebSocketClient] keeps a map
 * of these and lazily creates entries on the first file event for
 * each drive.
 *
 * Mirrors [DriveSync]'s job-serialization pattern (kept deliberately
 * close so the two paths are easy to reason about side by side):
 *  - [Mutex.tryLock] gives single-flight semantics — only one drain
 *    runs at a time per worker.
 *  - An atomic killroy bit captures "another file came in while we
 *    were busy"; the `finally` block re-launches a drain if it's set,
 *    exactly like [DriveSync.sync].
 *  - Pending files accumulate in a small queue while a drain runs;
 *    the next drain picks them up as one batch.
 *
 * Why batch: a peer sending several files back-to-back produces
 * several WS notifications in tight succession. Funnelling them into
 * one [MainIndexMetaHelpers.HomebaseFileProcessor.baseUpsertEntryZapZap]
 * call collapses N transactions into one — much faster, and matches
 * the shape that DriveSync produces for its own batches.
 *
 * The WS path does NOT advance a sync cursor — that's [DriveSync]'s
 * job. The per-reconnect catch-up via
 * `AuthConnectionCoordinator.onConnected → driveSyncManager.syncAll()`
 * is the backstop for anything missed during a transient disconnect.
 */
class DriveWebSocketUpsertWorker(
    private val identityId: Uuid,
    private val driveId: Uuid,
    databaseManager: DatabaseManager,
    private val eventBus: EventBus,
    scope: CoroutineScope? = null,
) {
    // Network/DB-bound work. Same dispatcher choice as DriveSync.
    private val scope = scope ?: supervisedScope("ws-upsert-worker")

    private val mutex = Mutex()
    private var job: Job? = null
    private val killroy = atomic(false)

    // Files awaiting drain. Producer ([submit]) adds; consumer
    // ([drainOnce]) snapshots & clears under [pendingMutex].
    private val pendingMutex = Mutex()
    private val pendingFiles = ArrayList<HomebaseFile>()

    private val fileHeaderProcessor =
        MainIndexMetaHelpers.HomebaseFileProcessor(databaseManager)

    /** Enqueue a decrypted file from a WS push and trigger a drain. */
    suspend fun submit(file: HomebaseFile) {
        pendingMutex.withLock { pendingFiles.add(file) }
        run()
    }

    /**
     * Single-flight launcher — mirrors [DriveSync.sync].
     * Returns the in-flight [Job], or null if another drain was already
     * running (in which case [killroy] flags it to recurse on finish).
     */
    private fun run(): Job? {
        if (!mutex.tryLock()) {
            killroy.value = true
            return null
        }
        job = scope.launch {
            try {
                drainOnce()
            } finally {
                job = null
                mutex.unlock()
            }
            if (killroy.value) {
                Logger.i { "WSPush: killroy triggered recursive drain for drive $driveId" }
                run()
            }
        }
        return job
    }

    private suspend fun drainOnce() {
        val batch: List<HomebaseFile> = pendingMutex.withLock {
            killroy.value = false
            if (pendingFiles.isEmpty()) return
            val snapshot = pendingFiles.toList()
            pendingFiles.clear()
            snapshot
        }

        try {
            val (written, upsertElapsed) = measureTimedValue {
                fileHeaderProcessor.baseUpsertEntryZapZap(
                    identityId = identityId,
                    driveId = driveId,
                    fileHeaders = batch,
                    cursor = null,
                )
            }

            // Emit only the rows that actually changed a DriveMainIndex record
            // (passed the timestamp guard), not the raw incoming batch. A stale or
            // duplicate server push — e.g. the half-stale `statisticsChanged`
            // fan-out copy whose `updated` is older than what we already hold — is
            // rejected by the guard and so is absent from `written`. Mapping the
            // raw batch instead would paint that rejected payload into the UI for a
            // frame (the reaction-highlight blink); honoring the guard here avoids
            // it and also collapses the fan-out duplicates to the single row that
            // won.
            //
            // Exception: a soft-delete must ALWAYS refresh an open conversation,
            // even when the guard rejected it because the REST DriveSync path
            // applied the same delete first (issue #1042). DriveSync is silent (no
            // BatchReceived), so skipping the guard-rejected WS delete too leaves
            // the live stream showing the message until a cold reload. A delete is
            // never a stale statisticsChanged duplicate, so surfacing it is safe.
            val writtenIds = written.mapTo(HashSet()) { it.fileId }
            val guardRejectedDeletes = batch
                .filter { it.isSoftDeleted() && it.fileId !in writtenIds }
                .distinctBy { it.fileId }
            val toEmit = if (guardRejectedDeletes.isEmpty()) written else written + guardRejectedDeletes

            if (toEmit.isEmpty()) {
                Logger.i(tag = "WSPush") {
                    "WSPush: drainOnce drive=$driveId rows=${batch.size} took=$upsertElapsed " +
                        "— no rows changed (timestamp guard); skipping BatchReceived"
                }
                return
            }

            Logger.i(tag = "WSPush") {
                "WSPush: drainOnce drive=$driveId rows=${batch.size} wrote=${written.size} " +
                    "deletes=${guardRejectedDeletes.size} took=$upsertElapsed (emitting BatchReceived)"
            }

            eventBus.emit(
                BackendEvent.DataEvent.BatchReceived(
                    driveId = driveId,
                    batchData = toEmit,
                )
            )
        } catch (e: Exception) {
            // The batch is dropped from the queue, but the next drive-sync
            // round (cold-boot or the per-reconnect syncAll catch-up) will
            // pick these files back up from the server cursor.
            Logger.e(e) {
                "WSPush: batch upsert FAILED for drive=$driveId rows=${batch.size}: ${e.message}"
            }
        }
    }

    /** Cancel the in-flight drain. Clears killroy so we don't relaunch
     *  after cancellation. */
    fun cancel() {
        killroy.value = false
        job?.cancel()
    }
}
