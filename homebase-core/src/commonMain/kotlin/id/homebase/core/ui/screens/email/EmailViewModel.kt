package id.homebase.core.ui.screens.email

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.mail.MailProvider
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.config.emailLabeledDrive
import id.homebase.core.email.EmailPreferences
import id.homebase.core.email.MailClientCatalog
import id.homebase.core.email.launchMailClient
import id.homebase.core.sync.OptionalDriveActivation
import id.homebase.core.ui.screens.email.setup.EmailSetupStep
import id.homebase.core.ui.screens.email.setup.resolveSetupStep
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Email setup's entry screen. Its whole job in this state is deciding which of three things the
 * user sees — "this server has no email", onboarding, or setup-in-progress — and getting the
 * email drive mounted when they say yes.
 *
 * Activation is never a local flag: it is the drive's mount state via [OptionalDriveActivation],
 * so it agrees across the user's devices.
 */
class EmailViewModel(
    private val emailPreferences: EmailPreferences,
    private val emailPermissionViewModel: ExtendPermissionViewModel,
    private val optionalDriveActivation: OptionalDriveActivation,
    private val mailProvider: MailProvider,
    private val emailStream: EmailStream,
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    companion object {
        private const val TAG = "EmailViewModel"
    }

    private val _uiState = MutableStateFlow(EmailUiState())
    val uiState: StateFlow<EmailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EmailUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EmailUiEvent> = _events.asSharedFlow()

    /** The dialog host; AppNavHost and the screens render this VM's dialog. */
    val emailExtendPermissionViewModel: ExtendPermissionViewModel
        get() = emailPermissionViewModel

    init {
        viewModelScope.launch {
            optionalDriveActivation.isActivatedFlow(emailLabeledDrive).collect { activated ->
                _uiState.update { it.copy(driveActivated = activated) }
            }
        }

        // The owner approves the drive in a browser, so the grant lands while we are backgrounded.
        // Mount as soon as the permission check sees it rather than making the user tap again.
        viewModelScope.launch {
            emailPermissionViewModel.permissionsGranted.filter { it }.collect {
                if (_uiState.value.driveActivated != true) {
                    activateDrive()
                }
            }
        }

        viewModelScope.launch {
            emailPreferences.selectedMailClientId.collect { id ->
                _uiState.update { it.copy(selectedMailClient = MailClientCatalog.byId(id)) }
            }
        }

        viewModelScope.launch {
            emailStream.credentials.collect { credentials ->
                _uiState.update { it.copy(credentialCount = credentials.size) }
            }
        }

        // AppModule notes onPostAuthenticated is deferred and often skipped on a warm relaunch,
        // so the stream is started here too rather than trusting it.
        emailStream.start()

        // This ViewModel is constructed by AppNavHost at composition — before login. Asking the
        // server anything then throws on missing credentials and leaves the screen stuck on an
        // error until the user retries by hand, so wait for credentials instead. Re-firing when
        // they change also refreshes after a login or an identity switch, which is when the
        // answer is most likely to have changed.
        viewModelScope.launch {
            credentialsManager.credentialsFlow.collect { credentials ->
                if (credentials != null) {
                    refreshStatus()
                }
            }
        }
    }

    /**
     * Where setup has got to, derived from the four signals rather than remembered. Recomputed
     * whenever any of them changes, which is what makes an interrupted setup resume itself.
     */
    val setupStep: StateFlow<EmailSetupStep> = combine(
        _uiState,
        emailPermissionViewModel.permissionsGranted,
    ) { state, granted ->
        resolveSetupStep(
            hasPermissions = granted,
            driveActivated = state.driveActivated == true,
            status = state.serverStatus,
            credentialCount = state.credentialCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EmailSetupStep.NeedsPermissions)

    fun onAction(action: EmailUiAction) {
        when (action) {
            EmailUiAction.SetupClicked -> {
                // Already granted (a second device, or a re-entry) — go straight to mounting.
                if (emailPermissionViewModel.permissionsGranted.value) {
                    viewModelScope.launch { activateDrive() }
                } else {
                    emailPermissionViewModel.recheckPermissions()
                }
            }

            EmailUiAction.DismissOnboardingClicked -> viewModelScope.launch {
                emailPreferences.setIconVisible(false)
                _events.tryEmit(EmailUiEvent.CloseOnboarding)
            }

            EmailUiAction.RefreshStatusClicked -> refreshStatus()

            EmailUiAction.OpenMailClientClicked -> viewModelScope.launch {
                val client = MailClientCatalog.byId(emailPreferences.selectedMailClientId.value)
                    ?: return@launch
                // False means not installed — say so rather than appearing to do nothing.
                if (!launchMailClient(client)) {
                    _events.tryEmit(EmailUiEvent.MailClientUnavailable(client.displayName))
                }
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch { refreshStatusNow() }
    }

    /** Awaitable form: setup needs the new status before deciding what to do next. */
    suspend fun refreshStatusNow() {
            _uiState.update { it.copy(isCheckingServer = true, statusError = null) }
            try {
                val status = mailProvider.getStatus()
                _uiState.update { it.copy(serverStatus = status, isCheckingServer = false) }

                // Only once email is actually on: before that there is no mailbox to ask about,
                // and a failure here must not make the whole screen look broken.
                if (status.activated) {
                    val mailbox = runCatching { mailProvider.getMailboxStatus() }.getOrNull()
                    _uiState.update { it.copy(mailboxStatus = mailbox) }
                }
            } catch (e: Exception) {
                // A failed call is not the same answer as "this server has no email" — the user
                // is told to retry rather than told their server does not support it.
                Logger.w(tag = TAG, throwable = e) { "mail status failed" }
                _uiState.update {
                    it.copy(isCheckingServer = false, statusError = EmailError.StatusUnavailable)
                }
            }
        }

    private suspend fun activateDrive() {
        optionalDriveActivation.activate(emailLabeledDrive)
        _events.tryEmit(EmailUiEvent.Activated)
        // The drive changes what the server reports (driveProvisioned), so re-ask.
        refreshStatus()
    }
}
