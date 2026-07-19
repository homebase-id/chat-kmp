package id.homebase.core.ui.screens.devmenu.scheduledpush

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.notifications.ScheduledPushNotificationEntry
import id.homebase.api.client.notifications.ScheduledPushNotificationOptions
import id.homebase.api.client.notifications.ScheduledPushNotificationProvider
import id.homebase.api.client.notifications.SchedulePushNotificationRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.core.config.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ScheduledPushTest"

/**
 * Dev-menu screen exercising every [ScheduledPushNotificationProvider] endpoint against the
 * caller's own identity: schedule (one-shot and recurring), list, update (reschedule), cancel.
 * Sends to self via [CredentialsManager.getActiveDomain] so a round trip is observable without a
 * second test identity.
 */
class DeveloperScheduledPushTestViewModel(
    private val scheduledPushNotificationProvider: ScheduledPushNotificationProvider,
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeveloperScheduledPushTestUiState())
    val uiState: StateFlow<DeveloperScheduledPushTestUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onUiAction(action: DeveloperScheduledPushTestUiAction) {
        when (action) {
            is DeveloperScheduledPushTestUiAction.Refresh -> refresh()
            is DeveloperScheduledPushTestUiAction.ScheduleOneShot -> schedule(recurrenceIntervalMs = null)
            is DeveloperScheduledPushTestUiAction.ScheduleRecurring -> schedule(
                recurrenceIntervalMs = ScheduledPushNotificationProvider.MIN_RECURRENCE_INTERVAL_MS
            )
            is DeveloperScheduledPushTestUiAction.Reschedule -> reschedule(action.entry)
            is DeveloperScheduledPushTestUiAction.Cancel -> cancel(action.jobId)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val entries = scheduledPushNotificationProvider.list()
                _uiState.update { it.copy(entries = entries, isLoading = false) }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "list() failed" }
                _uiState.update { it.copy(isLoading = false) }
                sendEvent(DeveloperScheduledPushTestUiEvent.Error(messageFor(e)))
            }
        }
    }

    private fun schedule(recurrenceIntervalMs: Long?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            try {
                val self = credentialsManager.getActiveDomain()
                val request = SchedulePushNotificationRequest(
                    options = ScheduledPushNotificationOptions(
                        appId = Uuid.parse(AppConfig.APP_ID),
                        typeId = Uuid.random(),
                        tagId = Uuid.random(),
                        silent = false,
                        recipients = self?.let { listOf(it) },
                        unEncryptedMessage = "Scheduled push test",
                    ),
                    sendAt = UnixTimeUtc(Clock.System.now().toEpochMilliseconds() + 10_000),
                    recurrenceInterval = recurrenceIntervalMs,
                )
                val jobId = scheduledPushNotificationProvider.schedule(request)
                Logger.i(tag = TAG) { "Scheduled jobId=$jobId recurring=${recurrenceIntervalMs != null}" }
                sendEvent(DeveloperScheduledPushTestUiEvent.Success("Scheduled — jobId=$jobId"))
                refresh()
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "schedule() failed" }
                sendEvent(DeveloperScheduledPushTestUiEvent.Error(messageFor(e)))
            } finally {
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    /** Exercises PUT — same jobId, same options/recurrence, sendAt pushed out by a minute. */
    private fun reschedule(entry: ScheduledPushNotificationEntry) {
        viewModelScope.launch {
            try {
                val request = SchedulePushNotificationRequest(
                    options = entry.options,
                    sendAt = UnixTimeUtc(Clock.System.now().toEpochMilliseconds() + 60_000),
                    recurrenceInterval = entry.recurrenceInterval,
                )
                scheduledPushNotificationProvider.update(entry.jobId, request)
                sendEvent(DeveloperScheduledPushTestUiEvent.Success("Rescheduled ${entry.jobId}"))
                refresh()
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "update() failed" }
                sendEvent(DeveloperScheduledPushTestUiEvent.Error(messageFor(e)))
            }
        }
    }

    private fun cancel(jobId: Uuid) {
        viewModelScope.launch {
            try {
                scheduledPushNotificationProvider.cancel(jobId)
                sendEvent(DeveloperScheduledPushTestUiEvent.Success("Cancelled $jobId"))
                refresh()
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "cancel() failed" }
                sendEvent(DeveloperScheduledPushTestUiEvent.Error(messageFor(e)))
            }
        }
    }

    private fun messageFor(e: Exception): String = when {
        e is ClientException && e.errorCode == OdinClientErrorCode.TooManyScheduledNotifications ->
            "Scheduled notification limit reached — cancel an existing one first."
        e is ClientException -> "Invalid request (${e.errorCode}): ${e.message}"
        e is NotFoundException ->
            "Not found — already fired, cancelled, or belongs to another app."
        else -> e.message ?: "Unknown error"
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: DeveloperScheduledPushTestUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }
}

@Immutable
data class DeveloperScheduledPushTestUiState(
    val entries: List<ScheduledPushNotificationEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val uiEvent: DeveloperScheduledPushTestUiEvent? = null,
)

sealed interface DeveloperScheduledPushTestUiEvent {
    data class Error(val message: String) : DeveloperScheduledPushTestUiEvent
    data class Success(val message: String) : DeveloperScheduledPushTestUiEvent
}

sealed interface DeveloperScheduledPushTestUiAction {
    data object Refresh : DeveloperScheduledPushTestUiAction
    data object ScheduleOneShot : DeveloperScheduledPushTestUiAction
    data object ScheduleRecurring : DeveloperScheduledPushTestUiAction
    data class Reschedule(val entry: ScheduledPushNotificationEntry) : DeveloperScheduledPushTestUiAction
    data class Cancel(val jobId: Uuid) : DeveloperScheduledPushTestUiAction
}
