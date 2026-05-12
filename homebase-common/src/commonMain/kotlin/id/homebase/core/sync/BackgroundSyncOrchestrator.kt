package id.homebase.core.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.sync.DriveSyncManager
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools

class BackgroundSyncOrchestrator(
    private val credentialsManager: CredentialsManager,
    private val driveSyncManager: DriveSyncManager,
    @Suppress("unused") private val driveFileHttpProvider: DriveFileHttpProvider,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
) {
    suspend fun syncIfAuthenticated(): SyncOutcome {
        Logger.d(tag = "BackgroundSync") { "syncIfAuthenticated: checking..." }
        if (!credentialsManager.hasActiveCredentials()) {
            Logger.i(tag = "BackgroundSync") { "syncIfAuthenticated: no credentials, skipping" }
            return SyncOutcome.NoCredentials
        }
        // If the WebSocket is live, the connection loop is already handling sync — no duplicate work needed.
        // Background sync is only meaningful when WS is offline (e.g. iOS background fetch).
        if (authConnectionCoordinator.isOnline.value) {
            Logger.d(tag = "BackgroundSync") { "syncIfAuthenticated: WS online — skipping (WS handles sync)" }
            return SyncOutcome.Success
        }
        Logger.i(tag = "BackgroundSync") { "syncIfAuthenticated: WS offline — running background sync" }
        return runCatching {
            driveSyncManager.start()

            // processInbox no longer needed — server auto-processes on QueryBatch.
            // processInboxViaHttp()

            driveSyncManager.syncAll()
        }.fold(
            onSuccess = {
                Logger.i(tag = "BackgroundSync") { "syncIfAuthenticated: completed" }
                SyncOutcome.Success
            },
            onFailure = {
                Logger.e(tag = "BackgroundSync") { "syncIfAuthenticated: failed — ${it.message}" }
                SyncOutcome.Failed(it)
            },
        )
    }

    /** Callback bridge for iOS interop — Kotlin suspend is not directly callable from Swift. */
    fun triggerSync(onComplete: (success: Boolean) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            val outcome = runCatching { syncIfAuthenticated() }.getOrElse { SyncOutcome.Failed(it) }
            onComplete(outcome is SyncOutcome.Success)
        }
    }

    /**
     * TODO TODD: Poke the inbox via HTTP here
     * Flush the server inbox over plain HTTP so that items transferred while the
     * WebSocket was offline become visible to QueryBatch.  This is the HTTP
     * counterpart of the WS "processInbox" command.
     */
    @Suppress("unused")
    private suspend fun processInboxViaHttp() {
        // processInbox no longer needed — server auto-processes on QueryBatch.
        // driveFileHttpProvider.processInbox(chatTargetDrive.alias)
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
