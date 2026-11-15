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
import kotlin.math.roundToInt
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

    /** What this process is preparing or the Outbox is sending for it, by uniqueId. */
    val working: StateFlow<Map<Uuid, BackupWork>> = _working.asStateFlow()

    private data class Row(val isVideo: Boolean, val handedOff: Boolean = false)

    // This process's rows until their last verdict: an Outbox retry sends one again after it left [working].
    private val rows = MutableStateFlow<Map<Uuid, Row>>(emptyMap())

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
        rows.value = emptyMap()
    }

    /** A video shows once its transcode reports: until then it can be waiting its turn behind another. */
    fun onPreparing(uniqueId: Uuid, isVideo: Boolean) {
        rows.update { it + (uniqueId to Row(isVideo)) }
        if (!isVideo) _working.update { it + (uniqueId to BackupWork(isVideo = false, stage = BackupWorkStage.Preparing)) }
    }

    /** Hand-off moved it on to uploading; anything else means it isn't being worked on now. */
    fun onPrepareEnded(uniqueId: Uuid) {
        rows.update { if (it[uniqueId]?.handedOff == true) it else it - uniqueId }
        _working.update { if (it[uniqueId]?.stage == BackupWorkStage.Preparing) it - uniqueId else it }
    }

    fun onHandedOff(uniqueId: Uuid) {
        _unsettled.update { it + uniqueId }
        // Gone already: a reset or an early verdict, and nothing would ever remove it again.
        val row = rows.value[uniqueId] ?: return
        rows.update { it + (uniqueId to row.copy(handedOff = true)) }
        _working.update { it + (uniqueId to BackupWork(row.isVideo, BackupWorkStage.Uploading)) }
    }

    /** The verdict beat the hand-off: [onEvent] already ran for it, so nothing will settle it later. */
    fun onSettledBeforeHandOff(uniqueId: Uuid) = settled(uniqueId, last = true)

    private fun onProgress(event: BackendEvent) {
        when (event) {
            is BackendEvent.OutboxEvent.ItemProgress -> if (event.driveId == photosDriveId) {
                _progress.update { it + 1 }
                val row = rows.value[event.uniqueId]
                if (event.isCreate && row?.handedOff == true) show(event.uniqueId, row, BackupWorkStage.Uploading, event.progress / 100f)
            }
            is BackendEvent.PayloadBundlingEvent.Video.PhaseProgress -> {
                val row = rows.value[event.uniqueId]
                if (row != null && !row.handedOff) {
                    show(event.uniqueId, row, BackupWorkStage.Preparing, videoOverallFraction(event.phase, event.progress))
                }
            }
            else -> Unit
        }
    }

    // Never backwards within a stage: ticks are emitted from separate coroutines and can land out of order.
    // An equal map doesn't re-emit, so a squeezed video phase repeating a percent redraws nothing.
    private fun show(uniqueId: Uuid, row: Row, stage: BackupWorkStage, fraction: Float) {
        val percent = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
        _working.update { work ->
            val current = work[uniqueId]?.takeIf { it.stage == stage } ?: BackupWork(row.isVideo, stage)
            work + (uniqueId to current.copy(percent = maxOf(current.percent ?: 0, percent)))
        }
    }

    private suspend fun onEvent(event: BackendEvent) {
        when (event) {
            is BackendEvent.OutboxEvent.ItemStarted -> if (event.driveId == photosDriveId) {
                ledger.onOutboxStarted(event.fileId)
            }
            is BackendEvent.OutboxEvent.ItemCompleted -> if (event.driveId == photosDriveId) {
                ledger.onOutboxCompleted(event.uniqueId)
                settled(event.uniqueId, last = true)
            }
            is BackendEvent.OutboxEvent.ItemFailed -> if (event.driveId == photosDriveId) {
                ledger.onOutboxRetrying(event.uniqueId)
                settled(event.uniqueId, last = false)
            }
            is BackendEvent.OutboxEvent.OutboxItemDropped -> if (event.driveId == photosDriveId) {
                Logger.w(tag = TAG) { "Outbox dropped ${event.uniqueId}: ${event.reason}" }
                ledger.onOutboxDropped(event.uniqueId, event.reason ?: "dropped by the Outbox")
                settled(event.uniqueId, last = true)
            }
            else -> Unit
        }
    }

    private fun settled(uniqueId: Uuid, last: Boolean) {
        _unsettled.update { it - uniqueId }
        _working.update { it - uniqueId }
        if (last) rows.update { it - uniqueId }
    }

    private companion object {
        const val TAG = "BackupOutboxResults"
    }
}
