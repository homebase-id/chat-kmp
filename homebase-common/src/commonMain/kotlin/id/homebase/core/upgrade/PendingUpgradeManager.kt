package id.homebase.core.upgrade

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.storage.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

sealed interface PendingUpgradeState {
    data object None : PendingUpgradeState
    data class ShowSnackbar(val upgradeUrl: String) : PendingUpgradeState
    data class ShowDialog(val upgradeUrl: String) : PendingUpgradeState
}

class PendingUpgradeManager(
    private val credentialsManager: CredentialsManager,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow<PendingUpgradeState>(PendingUpgradeState.None)
    val state: StateFlow<PendingUpgradeState> = _state.asStateFlow()

    private var dialogDismissedThisSession: Boolean = false

    suspend fun onUpgradeCheckResult(required: Boolean) {
        Logger.d(tag = TAG) { "onUpgradeCheckResult(required=$required)" }
        if (!required) {
            SharedPreferences.remove(KEY_FIRST_SEEN_MS)
            dialogDismissedThisSession = false
            _state.value = PendingUpgradeState.None
            return
        }

        var firstSeenMs = SharedPreferences.getLong(KEY_FIRST_SEEN_MS)
        if (firstSeenMs == 0L) {
            firstSeenMs = clock.now().toEpochMilliseconds()
            SharedPreferences.putLong(KEY_FIRST_SEEN_MS, firstSeenMs)
            Logger.d(tag = TAG) { "First upgrade detection recorded at $firstSeenMs" }
        }

        if (dialogDismissedThisSession) {
            Logger.d(tag = TAG) { "Dialog dismissed this session — suppressing" }
            _state.value = PendingUpgradeState.None
            return
        }

        val domain = credentialsManager.getActiveDomain()
        if (domain == null) {
            Logger.w(tag = TAG) { "No active credentials — cannot build upgrade URL" }
            _state.value = PendingUpgradeState.None
            return
        }

        val upgradeUrl = "https://${domain.domainName}/owner/settings/version-info"
        val nowMs = clock.now().toEpochMilliseconds()
        val elapsedDays = (nowMs - firstSeenMs) / (1000 * 60 * 60 * 24)

        _state.value = if (nowMs - firstSeenMs >= ESCALATION_THRESHOLD_MS) {
            Logger.i(tag = TAG) { "Upgrade pending for $elapsedDays days — escalating to dialog" }
            PendingUpgradeState.ShowDialog(upgradeUrl)
        } else {
            Logger.d(tag = TAG) { "Upgrade pending for $elapsedDays days — showing snackbar" }
            PendingUpgradeState.ShowSnackbar(upgradeUrl)
        }
    }

    fun dismissDialog() {
        dialogDismissedThisSession = true
        _state.value = PendingUpgradeState.None
    }

    fun reset() {
        Logger.d(tag = TAG) { "Resetting upgrade state (logout)" }
        SharedPreferences.remove(KEY_FIRST_SEEN_MS)
        dialogDismissedThisSession = false
        _state.value = PendingUpgradeState.None
    }

    companion object {
        private const val TAG = "PendingUpgradeManager"
        const val KEY_FIRST_SEEN_MS = "pending_upgrade_first_seen_ms"
        private val ESCALATION_THRESHOLD_MS = 7.days.inWholeMilliseconds
    }
}
