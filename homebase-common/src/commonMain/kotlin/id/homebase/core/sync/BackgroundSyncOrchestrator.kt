package id.homebase.core.sync

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.sync.DriveSyncManager
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.syncLabeledDrives
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools

class BackgroundSyncOrchestrator(
    private val credentialsManager: CredentialsManager,
    private val driveSyncManager: DriveSyncManager,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
) {
    suspend fun syncIfAuthenticated(): SyncOutcome {
        if (!credentialsManager.hasActiveCredentials()) return SyncOutcome.NoCredentials
        // If the WebSocket is live, the connection loop is already handling sync — no duplicate work needed.
        // Background sync is only meaningful when WS is offline (e.g. iOS background fetch).
        if (authConnectionCoordinator.isOnline.value) return SyncOutcome.Success
        return runCatching {
            driveSyncManager.start()
            driveSyncManager.syncAll()
        }.fold(
            onSuccess = { SyncOutcome.Success },
            onFailure = { SyncOutcome.Failed(it) },
        )
    }

    /** Callback bridge for iOS interop — Kotlin suspend is not directly callable from Swift. */
    fun triggerSync(onComplete: (success: Boolean) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            val outcome = runCatching { syncIfAuthenticated() }.getOrElse { SyncOutcome.Failed(it) }
            onComplete(outcome is SyncOutcome.Success)
        }
    }

    companion object {
        fun fromKoin(): BackgroundSyncOrchestrator = KoinPlatformTools.defaultContext().get().get()
    }
}

sealed interface SyncOutcome {
    data object Success : SyncOutcome
    data object NoCredentials : SyncOutcome
    data class Failed(val cause: Throwable) : SyncOutcome
}
