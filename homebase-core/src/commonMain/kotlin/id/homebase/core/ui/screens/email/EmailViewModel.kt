package id.homebase.core.ui.screens.email

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.mail.MailProvider
import id.homebase.api.client.mail.MailboxStatusResult
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.config.emailLabeledDrive
import id.homebase.core.email.EmailPreferences
import id.homebase.core.email.Thunderbird
import id.homebase.core.email.launchMailClient
import id.homebase.core.sync.OptionalDriveActivation
import id.homebase.core.util.buildOwnerEmailSettingsUrl
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

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
        private val HEALTH_REUSE = 15.minutes
    }

    /** When the last health answer arrived; HomeShown reuses one younger than [HEALTH_REUSE]. */
    private var lastHealthCheck: TimeSource.Monotonic.ValueTimeMark? = null
    private var healthJob: Job? = null

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
        //
        // Gated on the SERVER confirming the drive exists (driveProvisioned), not on the
        // permission check alone. Mounting is purely local - it does not create anything - so
        // mounting a drive the identity never created leaves DriveSync retrying `400
        // InvalidDrive` once a second forever, and writes the drive into the cross-device
        // registry, which makes it survive a restart and re-mount on every future login.
        viewModelScope.launch {
            emailPermissionViewModel.permissionsGranted.filter { it }.collect {
                // Re-read the status FIRST. The grant is what provisions the drive, so the
                // cached status here is by definition from before it existed: deciding on it
                // meant reading driveProvisioned=false, skipping activation, and leaving the
                // screen stuck with no way to refresh. Observed on a real setup — the status
                // fetched at login said drive=false, the grant landed 16s later, and nothing
                // ever asked again.
                runCatching { refreshStatusNow() }
                if (_uiState.value.driveActivated != true && _uiState.value.serverStatus?.driveProvisioned == true) {
                    activateDrive()
                }
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
            // On an identity switch, collectLatest cancels the previous identity's status call
            // and the reset drops everything it already answered, so none of it is shown (or
            // copied from) under the new identity. The drive and credential fields have their
            // own flows. A re-emission for the same identity (a token refresh) only re-asks.
            var identity = credentialsManager.credentialsFlow.value?.domain
            credentialsManager.credentialsFlow.collectLatest { credentials ->
                if (credentials?.domain != identity) {
                    identity = credentials?.domain
                    healthJob?.cancel()
                    lastHealthCheck = null
                    _uiState.update { EmailUiState(driveActivated = it.driveActivated, credentialCount = it.credentialCount) }
                }
                if (credentials != null) {
                    refreshStatusNow()
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
                // Straight to mounting only when the drive DEMONSTRABLY exists (a second device,
                // or a re-entry). Granted-but-not-provisioned means the approval flow has not
                // actually run for this identity, and mounting then just starts the InvalidDrive
                // retry loop - so fall through to the permission flow, which is what creates it.
                if (emailPermissionViewModel.permissionsGranted.value &&
                    _uiState.value.serverStatus?.driveProvisioned == true
                ) {
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

            EmailUiAction.CheckHealthClicked -> checkHealth()

            // The home re-enters composition on every return from a sub-screen; each check costs
            // the server uncached DNS lookups and a relay call, so a recent answer is reused.
            EmailUiAction.HomeShown -> {
                val fresh = lastHealthCheck?.let { it.elapsedNow() < HEALTH_REUSE } == true
                if (!fresh) checkHealth()
            }

            EmailUiAction.OpenMailClientClicked -> viewModelScope.launch {
                val client = Thunderbird.client
                // False means not installed — say so rather than appearing to do nothing.
                if (!launchMailClient(client)) {
                    _events.tryEmit(EmailUiEvent.MailClientUnavailable(client.displayName))
                }
            }
        }
    }

    /**
     * Ask the server whether email actually works.
     *
     * Every check runs server-side, from the same services the owner console's Email tab uses,
     * and the verdict (`severity`, `brokenRecords`) arrives already decided. Nothing is
     * recomputed here on purpose: two clients deriving "healthy" from raw records would
     * eventually disagree about the same identity, and the one that disagreed quietly would be
     * the one people trusted.
     */
    fun checkHealth() {
        if (healthJob?.isActive == true) return
        healthJob = viewModelScope.launch {
            _uiState.update { it.copy(isCheckingHealth = true, healthError = null) }
            try {
                val health = mailProvider.getHealth()
                lastHealthCheck = TimeSource.Monotonic.markNow()
                _uiState.update { it.copy(health = health, isCheckingHealth = false) }
            } catch (e: CancellationException) {
                // An identity switch: the reset already cleared this check's state.
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "email health check failed: ${e.message}" }
                _uiState.update {
                    it.copy(isCheckingHealth = false, healthError = EmailError.HealthUnavailable)
                }
            }
        }
    }

    /** Where the owner fixes what the setup check found; null when signed out. */
    fun ownerEmailSettingsUrl(): String? =
        credentialsManager.credentialsFlow.value?.domain?.buildOwnerEmailSettingsUrl()

    fun refreshStatus() {
        viewModelScope.launch { refreshStatusNow() }
    }

    /** Awaitable form: setup needs the new status before deciding what to do next. */
    suspend fun refreshStatusNow() {
            _uiState.update { it.copy(isCheckingServer = true, statusError = null) }
            try {
                val status = mailProvider.getStatus()
                _uiState.update { it.copy(serverStatus = status, isCheckingServer = false) }
                // Remembered for the toolbar, which has to decide before this call can finish.
                emailPreferences.setServerSupportsMail(status.tenantMailEnabled)

                // Only once email is actually on: before that there is no mailbox to ask about,
                // and a failure here must not make the whole screen look broken.
                // A failed call is "did not report", the same answer as a server that does not
                // count; null stays "not asked yet".
                if (status.activated) {
                    val mailbox = runCatching { mailProvider.getMailboxStatus() }
                        .getOrElse {
                            if (it is CancellationException) throw it
                            MailboxStatusResult(available = false)
                        }
                    _uiState.update { it.copy(mailboxStatus = mailbox) }
                }
            } catch (e: CancellationException) {
                throw e
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
