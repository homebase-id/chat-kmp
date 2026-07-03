package id.homebase.core.ui.navigation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.notifications.BadgeManager
import id.homebase.core.notifications.NotificationNavigationEvent
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.RichNotificationData
import id.homebase.core.permission.registerPermissionCallbackHandler
import id.homebase.core.permission.unregisterPermissionCallbackHandler
import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.share.ShareContentProcessor
import id.homebase.core.share.registerMomentShareHandler
import id.homebase.core.share.registerShareHandler
import id.homebase.core.share.sharedMediaAttachment
import id.homebase.core.share.unregisterMomentShareHandler
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

class AppViewModel(
    private val credentialsManager: CredentialsManager,
    private val notificationService: NotificationService,
    private val shareContentProcessor: ShareContentProcessor,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val updateAppManager: UpdateAppManager,
    private val eventBus: EventBus,
    private val pendingUpgradeManager: PendingUpgradeManager,
    private val momentCreateFlowState: MomentCreateFlowState,
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

    init {
        collectNotificationEvents()
        registerShareHandler { conversationId -> handleShareIntent(conversationId) }
        registerMomentShareHandler { handleMomentShareIntent() }
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
        unregisterMomentShareHandler()
        unregisterPermissionCallbackHandler()
        unregisterDataUpgradeCallbackHandler()
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
        notificationService.isAppInForeground = true
        authConnectionCoordinator.setForeground(true)
        refreshData()
        checkForUpdate()
        // Reset the icon-badge counter without wiping the tray — notifications
        // clear per conversation when the user taps or reads them, so opening
        // the app from the launcher leaves other senders' notifications intact.
        BadgeManager.resetCount()
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

    /**
     * Called when the app receives a "New Moment" share (iOS: via the
     * `homebase-share://moment` URL scheme). Reads the media the share extension
     * staged in the App Group, seeds the moments composer draft (the same
     * [MomentCreateFlowState] the Android share path seeds), and navigates to
     * the composer via [NotificationNavigationEvent.OpenMomentCompose].
     *
     * Deliberately does NOT call [ShareContentProcessor.cleanup] — that deletes
     * the staged files, which the composer/upload still needs. As on Android,
     * the composer owns the files from here; the next share overwrites the
     * staging area.
     */
    fun handleMomentShareIntent() {
        val descriptor = shareContentProcessor.readPendingContent()
        if (descriptor == null || descriptor.fileNames.isEmpty()) {
            Logger.w(tag = "AppViewModel") { "Moment share: no pending media to compose" }
            return
        }
        val attachments = descriptor.fileNames.zip(descriptor.mimeTypes).map { (name, mime) ->
            sharedMediaAttachment(shareContentProcessor.resolveFilePath(name), mime)
        }
        Logger.i(tag = "AppViewModel") { "Moment share: seeding draft with ${attachments.size} attachments" }
        momentCreateFlowState.setDraft(
            MomentCreateFlowState.Draft(
                attachments = attachments,
                description = descriptor.text ?: "",
            )
        )
        _navigationEvents.trySend(NotificationNavigationEvent.OpenMomentCompose)
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
