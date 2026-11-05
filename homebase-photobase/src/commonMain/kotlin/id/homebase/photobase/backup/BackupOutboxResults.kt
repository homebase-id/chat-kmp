package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** Turns the Outbox's verdicts on Photos drive rows into ledger states. */
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

    private val _activity = MutableStateFlow(0L)

    /** Ticks on anything the Outbox reports for the Photos drive, progress included. */
    val activity: StateFlow<Long> = _activity.asStateFlow()

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
    }

    fun onHandedOff(uniqueId: Uuid) {
        _unsettled.update { it + uniqueId }
    }

    private fun onProgress(event: BackendEvent) {
        if (event is BackendEvent.OutboxEvent.ItemProgress && event.driveId == photosDriveId) _activity.update { it + 1 }
    }

    private suspend fun onEvent(event: BackendEvent) {
        when (event) {
            is BackendEvent.OutboxEvent.ItemStarted -> if (event.driveId == photosDriveId) {
                ledger.onOutboxStarted(event.fileId)
                _activity.update { it + 1 }
            }
            is BackendEvent.OutboxEvent.ItemCompleted -> if (event.driveId == photosDriveId) {
                ledger.onOutboxCompleted(event.uniqueId)
                settled(event.uniqueId)
            }
            is BackendEvent.OutboxEvent.ItemFailed -> if (event.driveId == photosDriveId) {
                ledger.onOutboxRetrying(event.uniqueId, null)
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
        _activity.update { it + 1 }
    }

    private companion object {
        const val TAG = "BackupOutboxResults"
    }
}
