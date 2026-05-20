package id.homebase.core.ui.navigation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.notifications.BadgeManager
import id.homebase.core.notifications.NotificationNavigationEvent
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.RichNotificationData
import id.homebase.core.permission.registerPermissionCallbackHandler
import id.homebase.core.permission.unregisterPermissionCallbackHandler
import id.homebase.core.share.ShareContentProcessor
import id.homebase.core.share.registerShareHandler
import id.homebase.core.share.unregisterShareHandler
import id.homebase.core.updater.UpdateAppManager
import id.homebase.core.upgrade.PendingUpgradeManager
import id.homebase.core.upgrade.PendingUpgradeState
import id.homebase.core.upgrade.registerDataUpgradeCallbackHandler
import id.homebase.core.upgrade.unregisterDataUpgradeCallbackHandler
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
import kotlin.coroutines.cancellation.CancellationException

class AppViewModel(
    private val connectionRequestService: ConnectionRequestService,
    private val credentialsManager: CredentialsManager,
    private val notificationService: NotificationService,
    private val shareContentProcessor: ShareContentProcessor,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val updateAppManager: UpdateAppManager,
    private val eventBus: EventBus,
    private val pendingUpgradeManager: PendingUpgradeManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    // Channel (not SharedFlow) so events forwarded from NotificationService are queued until
    // AppNavHost's collector attaches. A SharedFlow with replay=0 would drop events emitted
    // before the first subscriber — e.g. a notification tap processed during cold start.
    private val _navigationEvents = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
    val navigationEvents: Flow<NotificationNavigationEvent> = _navigationEvents.receiveAsFlow()

    private var credentialsJob: Job? = null
    private var listenForConnectionRequestsJob: Job? = null
    private var upgradeCheckJob: Job? = null

    init {
        collectNotificationEvents()
        registerShareHandler { conversationId -> handleShareIntent(conversationId) }
        registerPermissionCallbackHandler { canceled ->
            viewModelScope.launch {
                eventBus.emit(
                    if (canceled) BackendEvent.PermissionsExtensionCanceled
                    else BackendEvent.PermissionsExtensionReturned
                )
            }
        }
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
        registerDataUpgradeCallbackHandler {
            viewModelScope.launch {
                eventBus.emit(BackendEvent.DataUpgradeReturned)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterShareHandler()
        unregisterPermissionCallbackHandler()
        unregisterDataUpgradeCallbackHandler()
    }

    fun refreshData() {
        credentialsJob?.cancel()
        credentialsJob = viewModelScope.launch {
            credentialsManager.credentialsFlow.collect { credentials ->
                if (credentials != null) {
                    _uiState.update { it.copy(currentOdinId = credentials.domain) }
                    listenForConnectionRequests()
                    checkPendingUpgrade()
                } else {
                    listenForConnectionRequestsJob?.cancel()
                }
            }
        }
    }

    /** Called when the app enters RESUMED state. */
    fun onResumed() {
        notificationService.isAppInForeground = true
        authConnectionCoordinator.setForeground(true)
        refreshData()
        checkForUpdate()
        BadgeManager.clear()
    }

    /** Called when the app leaves RESUMED state. */
    fun onPaused() {
        notificationService.isAppInForeground = false
        authConnectionCoordinator.setForeground(false)
    }

    /** Collects notification events from NotificationService and forwards to UI. */
    private fun collectNotificationEvents() {
        viewModelScope.launch {
            notificationService.navigationEvents.collect { event ->
                Logger.i(tag = "AppViewModel") { "navigationEvent forwarded: $event" }
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
            updateAppManager.downloadUpdate()
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            val result = updateAppManager.checkForUpdate()
            _uiState.update { it.copy(updateAvailable = result.updateAvailable && result.canUpdate, updateAvailableVersion = result.versionName ?: "") }
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

    /**
     * Called when the app receives a share intent (iOS: via homebase-share:// URL scheme).
     * Navigates to the target conversation. The conversation screen picks up
     * pending shared content from [ShareContentProcessor].
     */
    fun handleShareIntent(conversationId: String) {
        Logger.i(tag = "AppViewModel") { "Handling share intent for conversation: $conversationId" }
        val pending = shareContentProcessor.readPendingContent()
        if (pending != null) {
            _navigationEvents.trySend(
                NotificationNavigationEvent.OpenConversation(
                    conversationId = conversationId,
                    source = NotificationNavigationEvent.OpenConversation.Source.ShareIntent,
                )
            )
        } else {
            Logger.w(tag = "AppViewModel") { "No pending shared content found for conversation: $conversationId" }
        }
    }

    private fun listenForConnectionRequests() {
        listenForConnectionRequestsJob?.cancel()
        listenForConnectionRequestsJob = viewModelScope.launch {
            try {
                connectionRequestService.start()
                connectionRequestService.incomingRequests.collect { incomingRequests ->
                    _uiState.update { it.copy(incomingRequests = incomingRequests) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(
                    throwable = e,
                    tag = "AppViewModel"
                ) { "Failed to collect incoming requests: ${e.message}" }
            }
        }
    }
}

@Immutable
data class AppUiState(
    val currentOdinId: OdinId? = null,
    val incomingRequests: List<IncomingConnectionRequestUiModel> = listOf(),
    val inAppNotification: RichNotificationData? = null,
    val updateAvailable: Boolean = false,
    val updateAvailableVersion: String = "",
    val pendingUpgrade: PendingUpgradeState = PendingUpgradeState.None,
)
