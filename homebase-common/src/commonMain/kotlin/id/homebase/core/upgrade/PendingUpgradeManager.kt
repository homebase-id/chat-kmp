package id.homebase.core.upgrade

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.upgrade.UpgradeStatus
import id.homebase.api.storage.SharedPreferences
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The owner console's data-upgrade page for [domainName], sending the owner back to [returnUrl]
 * when it is non-empty.  The one place this link is built: the pending-upgrade prompt and the
 * connect dialog's "your identity needs an update" refusal both open it.
 */
fun dataUpgradeUrl(domainName: String, returnUrl: String): String = buildString {
    append("https://$domainName/owner/data-upgrade")
    if (returnUrl.isNotEmpty()) {
        append("?returnUrl=")
        append(returnUrl.encodeURLParameter())
    }
}

sealed interface PendingUpgradeState {
    data object None : PendingUpgradeState
    data class ShowSnackbar(val upgradeUrl: String, val epoch: Long = 0) : PendingUpgradeState
    data class ShowDialog(val upgradeUrl: String, val epoch: Long = 0) : PendingUpgradeState
    data class UpgradeRunning(val epoch: Long = 0) : PendingUpgradeState
}

class PendingUpgradeManager(
    private val credentialsManager: CredentialsManager,
    private val checkUpgradeStatus: suspend () -> UpgradeStatus,
    private val dataUpgradeReturnUrl: () -> String = { "" },
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow<PendingUpgradeState>(PendingUpgradeState.None)
    val state: StateFlow<PendingUpgradeState> = _state.asStateFlow()

    private var dialogLogged = false
    private var epoch = 0L

    suspend fun checkUpgrade() {
        val status = try {
            checkUpgradeStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "Upgrade check failed: ${e.message}" }
            return
        }
        onUpgradeCheckResult(status)
    }

    internal suspend fun onUpgradeCheckResult(status: UpgradeStatus) {
        Logger.d(tag = TAG) { "onUpgradeCheckResult(status=$status)" }
        if (status == UpgradeStatus.NONE) {
            SharedPreferences.remove(KEY_FIRST_SEEN_MS)
            _state.value = PendingUpgradeState.None
            return
        }

        if (status == UpgradeStatus.RUNNING) {
            val nextEpoch = ++epoch
            _state.value = PendingUpgradeState.UpgradeRunning(nextEpoch)
            return
        }

        var firstSeenMs = SharedPreferences.getLong(KEY_FIRST_SEEN_MS)
        if (firstSeenMs == 0L) {
            firstSeenMs = clock.now().toEpochMilliseconds()
            SharedPreferences.putLong(KEY_FIRST_SEEN_MS, firstSeenMs)
            Logger.d(tag = TAG) { "First upgrade detection recorded at $firstSeenMs" }
        }

        val domain = credentialsManager.getActiveDomain()
        if (domain == null) {
            Logger.w(tag = TAG) { "No active credentials — cannot build upgrade URL" }
            _state.value = PendingUpgradeState.None
            return
        }

        val upgradeUrl = dataUpgradeUrl(domain.domainName, dataUpgradeReturnUrl())
        val nowMs = clock.now().toEpochMilliseconds()
        val elapsedDays = (nowMs - firstSeenMs) / (1000 * 60 * 60 * 24)

        val nextEpoch = ++epoch
        _state.value = if (nowMs - firstSeenMs >= ESCALATION_THRESHOLD_MS) {
            Logger.i(tag = TAG) { "Upgrade pending for $elapsedDays days — escalating to dialog" }
            if (!dialogLogged) {
                dialogLogged = true
                Logger.i(tag = TAG) { "upgrade dialog shown (not dismissable)" }
            }
            PendingUpgradeState.ShowDialog(upgradeUrl, nextEpoch)
        } else {
            Logger.d(tag = TAG) { "Upgrade pending for $elapsedDays days — showing snackbar" }
            PendingUpgradeState.ShowSnackbar(upgradeUrl, nextEpoch)
        }
    }

    fun reset() {
        Logger.d(tag = TAG) { "Resetting upgrade state (logout)" }
        SharedPreferences.remove(KEY_FIRST_SEEN_MS)
        _state.value = PendingUpgradeState.None
    }

    companion object {
        private const val TAG = "PendingUpgradeManager"
        const val KEY_FIRST_SEEN_MS = "pending_upgrade_first_seen_ms"
        private val ESCALATION_THRESHOLD_MS = 7.days.inWholeMilliseconds
    }
}
