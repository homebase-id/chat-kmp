package id.homebase.core.ui.navigation

import id.homebase.photobase.backup.PhotoBackupEntry
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.notifications.BadgeManager
import id.homebase.core.notifications.NotificationNavigationEvent
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.RichNotificationData
import id.homebase.core.updater.AppUpdates
import id.homebase.core.updater.canOfferUpdate
import id.homebase.core.upgrade.PendingUpgradeManager
import id.homebase.core.upgrade.PendingUpgradeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.measureTime

class AppViewModel(
    private val credentialsManager: CredentialsManager,
    private val notificationService: NotificationService,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val appUpdates: AppUpdates,
    private val eventBus: EventBus,
    private val pendingUpgradeManager: PendingUpgradeManager,
    private val conversationStream: ConversationStream,
    private val photoBackup: PhotoBackupEntry,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    // Channel (not SharedFlow) so events forwarded from NotificationService are queued until
    // AppNavHost's collector attaches. A SharedFlow with replay=0 would drop events emitted
    // before the first subscriber — e.g. a notification tap processed during cold start.
    private val _navigationEvents = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
    val navigationEvents: Flow<NotificationNavigationEvent> = _navigationEvents.receiveAsFlow()

    private var credentialsJob: Job? = null
    private var upgradeCheckJob: Job? = null

    // Diagnostic only. AppViewModel must be resolved with koinViewModel() so the
    // Activity's ViewModelStore owns exactly one live instance and clears it on
    // destroy. When it was resolved with koinInject() instead, every Activity
    // recreation leaked another instance whose collectNotificationEvents() kept
    // competing for NotificationService's single-consumer nav Channel — an orphan
    // winning a turn swallowed the tap. That failure was invisible in the log
    // (one "forwarded" line either way); this makes it loud.
    private val instanceId = ++instancesCreated

    init {
        liveInstances++
        if (liveInstances > 1) {
            Logger.w(tag = "AppViewModel") {
                "instance #$instanceId created while $liveInstances are live — notification " +
                    "taps will be round-robined into orphan channels. AppViewModel must be " +
                    "resolved with koinViewModel(), not koinInject()."
            }
        }
        collectNotificationEvents()
        collectUpdateAvailability()
        syncIconBadge()
        viewModelScope.launch {
            pendingUpgradeManager.state.collect { upgradeState ->
                _uiState.update { it.copy(pendingUpgrade = upgradeState) }
            }
        }
        viewModelScope.launch {
            eventBus.events.filterIsInstance<BackendEvent.DataUpgradeReturned>().collect {
                checkPendingUpgrade()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        liveInstances--
    }

    fun refreshData() {
        credentialsJob?.cancel()
        credentialsJob = viewModelScope.launch {
            credentialsManager.credentialsFlow.collect { credentials ->
                if (credentials != null) {
                    _uiState.update { it.copy(currentOdinId = credentials.domain) }
                    checkPendingUpgrade()
                }
            }
        }
    }

    /** Called when the app enters RESUMED state. */
    fun onResumed() {
        val took = measureTime {
            notificationService.isAppInForeground = true
            authConnectionCoordinator.setForeground(true)
            photoBackup.onAppForeground()
            refreshData()
            checkForUpdate()
            // Re-assert the real total rather than zeroing: the iOS extension has been counting
            // pushes on its own while backgrounded, and messages still unread must stay shown.
            conversationStream.currentUnreadTotal()?.let { BadgeManager.setCount(it) }
        }
        Logger.i(tag = "AppViewModel") { "onResumed took ${took.inWholeMilliseconds}ms" }
    }

    /** Called when the app leaves RESUMED state. */
    fun onPaused() {
        notificationService.isAppInForeground = false
        authConnectionCoordinator.setForeground(false)
        photoBackup.onAppBackground()
    }

    /** Keeps the app-icon badge in lock-step with the real unread total. */
    private fun syncIconBadge() {
        viewModelScope.launch {
            conversationStream.totalUnreadCount.collect { BadgeManager.setCount(it) }
        }
    }

    /** Collects notification events from NotificationService and forwards to UI. */
    private fun collectNotificationEvents() {
        viewModelScope.launch {
            notificationService.navigationEvents.collect { event ->
                Logger.i(tag = "AppViewModel") {
                    "navigationEvent forwarded (vm#$instanceId): $event"
                }
                _navigationEvents.trySend(event)
            }
        }
        viewModelScope.launch {
            notificationService.inAppNotificationEvents.collect { event ->
                _uiState.update { it.copy(inAppNotification = event) }
            }
        }
    }

    fun dismissInAppBanner() {
        _uiState.update { it.copy(inAppNotification = null) }
    }

    fun triggerUpdate() {
        viewModelScope.launch {
            appUpdates.download()
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch { appUpdates.check() }
    }

    private fun collectUpdateAvailability() {
        viewModelScope.launch {
            appUpdates.latest.collect { latest ->
                _uiState.update {
                    it.copy(
                        updateAvailable = latest.canOfferUpdate,
                        updateAvailableVersion = latest?.versionName ?: "",
                    )
                }
            }
        }
    }

    private fun checkPendingUpgrade() {
        upgradeCheckJob?.cancel()
        upgradeCheckJob = viewModelScope.launch {
            pendingUpgradeManager.checkUpgrade()
        }
    }

    fun dismissUpgradeDialog() {
        pendingUpgradeManager.dismissDialog()
    }

    /** Routes an in-app banner tap through NotificationService's click handler. */
    fun onInAppBannerTapped(payloadData: Map<String, String>) {
        dismissInAppBanner()
        notificationService.handleNotificationClicked(payloadData)
    }

    private companion object {
        // Main-thread only (ViewModel construction / clearing), so plain vars.
        var instancesCreated = 0
        var liveInstances = 0
    }
}

@Immutable
data class AppUiState(
    val currentOdinId: OdinId? = null,
    val inAppNotification: RichNotificationData? = null,
    val updateAvailable: Boolean = false,
    val updateAvailableVersion: String = "",
    val pendingUpgrade: PendingUpgradeState = PendingUpgradeState.None,
)
