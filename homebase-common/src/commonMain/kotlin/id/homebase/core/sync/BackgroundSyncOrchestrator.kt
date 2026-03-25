package id.homebase.core.sync

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.sync.DriveSyncManager
import id.homebase.core.config.syncLabeledDrives
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class BackgroundSyncOrchestrator(
    private val credentialsManager: CredentialsManager,
    private val driveSyncManager: DriveSyncManager,
) {
    suspend fun syncIfAuthenticated(): SyncOutcome {
        if (!credentialsManager.hasActiveCredentials()) return SyncOutcome.NoCredentials
        return runCatching {
            driveSyncManager.start(syncLabeledDrives.associate { it.drive.alias to it.label })
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
        fun fromKoin(): BackgroundSyncOrchestrator = GlobalContext.get().get()
    }
}

sealed interface SyncOutcome {
    data object Success : SyncOutcome
    data object NoCredentials : SyncOutcome
    data class Failed(val cause: Throwable) : SyncOutcome
}
