package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.video.videoOverallFraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** Turns the Outbox's verdicts on Photos drive rows into ledger states, and tracks how far this process's rows are. */
class BackupOutboxResults(
    private val ledger: BackupLedger,
    private val eventBus: EventBus,
    private val photosDriveId: Uuid,
    private val scope: CoroutineScope,
) {
    private var jobs: List<Job> = emptyList()

    private val _unsettled = MutableStateFlow<Set<Uuid>>(emptySet())

    /** What this process handed to the Outbox and hasn't heard a verdict on. */
    val unsettled: StateFlow<Set<Uuid>> = _unsettled.asStateFlow()

    private val _progress = MutableStateFlow(0L)

    /** Ticks on upload progress for the Photos drive: verdicts show in [unsettled] and the ledger. */
    val progress: StateFlow<Long> = _progress.asStateFlow()

    private val _working = MutableStateFlow<Map<Uuid, BackupWork>>(emptyMap())

    /** What this process is preparing or has handed to the Outbox, by uniqueId. */
    val working: StateFlow<Map<Uuid, BackupWork>> = _working.asStateFlow()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { eventBus.events.collect { onEvent(it) } },
            scope.launch { eventBus.progress.collect { onProgress(it) } },
        )
    }

    fun reset() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _unsettled.value = emptySet()
        _working.value = emptyMap()
    }

    fun onPreparing(uniqueId: Uuid, isVideo: Boolean) {
        _working.update { it + (uniqueId to BackupWork(isVideo, BackupWorkStage.Preparing)) }
    }

    /** Hand-off moved it on to uploading; anything else means it isn't being worked on now. */
    fun onPrepareEnded(uniqueId: Uuid) {
        _working.update { if (it[uniqueId]?.stage == BackupWorkStage.Preparing) it - uniqueId else it }
    }

    fun onHandedOff(uniqueId: Uuid) {
        _unsettled.update { it + uniqueId }
        _working.update { work ->
            val isVideo = work[uniqueId]?.isVideo ?: false
            work + (uniqueId to BackupWork(isVideo, BackupWorkStage.Uploading))
        }
    }

    /** The verdict beat the hand-off: [onEvent] already ran for it, so nothing will settle it later. */
    fun onSettledBeforeHandOff(uniqueId: Uuid) = settled(uniqueId)

    private fun onProgress(event: BackendEvent) {
        when (event) {
            is BackendEvent.OutboxEvent.ItemProgress -> if (event.driveId == photosDriveId) {
                _progress.update { it + 1 }
                if (event.isCreate) advance(event.uniqueId, BackupWorkStage.Uploading, event.progress / 100f)
            }
            is BackendEvent.PayloadBundlingEvent.Video.PhaseProgress ->
                advance(event.uniqueId, BackupWorkStage.Preparing, videoOverallFraction(event.phase, event.progress))
            else -> Unit
        }
    }

    // Whole percents only: a transcode reports many times a second, and each update redraws the status.
    private fun advance(uniqueId: Uuid, stage: BackupWorkStage, fraction: Float) {
        val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
        _working.update { work ->
            val current = work[uniqueId]?.takeIf { it.stage == stage } ?: return@update work
            if (current.fraction?.let { (it * 100).toInt() } == percent) work
            else work + (uniqueId to current.copy(fraction = percent / 100f))
        }
    }

    private suspend fun onEvent(event: BackendEvent) {
        when (event) {
            is BackendEvent.OutboxEvent.ItemStarted -> if (event.driveId == photosDriveId) {
                ledger.onOutboxStarted(event.fileId)
            }
            is BackendEvent.OutboxEvent.ItemCompleted -> if (event.driveId == photosDriveId) {
                ledger.onOutboxCompleted(event.uniqueId)
                settled(event.uniqueId)
            }
            is BackendEvent.OutboxEvent.ItemFailed -> if (event.driveId == photosDriveId) {
                ledger.onOutboxRetrying(event.uniqueId)
                settled(event.uniqueId)
            }
            is BackendEvent.OutboxEvent.OutboxItemDropped -> if (event.driveId == photosDriveId) {
                Logger.w(tag = TAG) { "Outbox dropped ${event.uniqueId}: ${event.reason}" }
                ledger.onOutboxDropped(event.uniqueId, event.reason ?: "dropped by the Outbox")
                settled(event.uniqueId)
            }
            else -> Unit
        }
    }

    private fun settled(uniqueId: Uuid) {
        _unsettled.update { it - uniqueId }
        _working.update { it - uniqueId }
    }

    private companion object {
        const val TAG = "BackupOutboxResults"
    }
}
