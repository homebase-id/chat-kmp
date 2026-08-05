package id.homebase.core.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatformTools
import kotlin.time.Duration.Companion.seconds

/**
 * @param authState narrow seam onto [id.homebase.api.youauth.YouAuthFlowManager.authState].
 *   Taken as a `StateFlow<YouAuthState>` (not the whole manager) so the
 *   credential-race fix is unit-testable without constructing a real
 *   YouAuthFlowManager and its DriveSyncManager / HttpClient deps.
 */
class BackgroundSyncOrchestrator(
    private val credentialsManager: CredentialsManager,
    private val driveSyncManager: DriveSyncManager,
    @Suppress("unused") private val driveFileHttpProvider: DriveFileHttpProvider,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val authState: StateFlow<YouAuthState>,
) {
    suspend fun syncIfAuthenticated(): SyncOutcome {
        Logger.d(tag = "BackgroundSync") { "syncIfAuthenticated: checking..." }

        // Close the ~12 ms FCM-vs-credentials-restore race observed in the wild
        // (see [awaitAuthRestored] kdoc for the full mechanism).
        val initialState = authState.value
        val resolved = awaitAuthRestored(authState, AUTH_RESTORE_TIMEOUT)
        if (resolved == null) {
            Logger.w(tag = "BackgroundSync") {
                "syncIfAuthenticated: auth state still Initializing after " +
                    "${AUTH_RESTORE_TIMEOUT.inWholeSeconds}s — skipping"
            }
            return SyncOutcome.NoCredentials
        }
        if (initialState is YouAuthState.Initializing) {
            Logger.i(tag = "BackgroundSync") {
                "syncIfAuthenticated: awaited credentials restoration — resolved to ${resolved::class.simpleName}"
            }
        }

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
            // The connect sequence serves the local drive set and reconciles with the server
            // in the background, so a drive activated on another device may not be mounted
            // yet. This syncAll() can be the only pass it gets before the process dies, and
            // nothing here is user-visible, so this is the one caller that waits for it.
            authConnectionCoordinator.awaitRegistryReconcile()
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
        /**
         * Bound to the observed FCM credential-restore race window with comfortable
         * headroom: in the homebase.log capture the restoration completed in ~12 ms;
         * we use 2 s so a slow-disk or contended-Koin-init case still resolves
         * cleanly, while staying well under the WorkManager job's overall budget.
         */
        private val AUTH_RESTORE_TIMEOUT = 2.seconds

        fun fromKoin(): BackgroundSyncOrchestrator = KoinPlatformTools.defaultContext().get().get()
    }
}

sealed interface SyncOutcome {
    data object Success : SyncOutcome
    data object NoCredentials : SyncOutcome
    data class Failed(val cause: Throwable) : SyncOutcome
}

/**
 * Waits for [authState] to leave [YouAuthState.Initializing] within [timeout].
 *
 * Closes the FCM-cold-wake credential-restore race:
 * `YouAuthFlowManager.restoreSession()` runs asynchronously on
 * construction, so the in-memory credentials don't exist until its disk
 * I/O completes. An FCM-driven `BackgroundSyncOrchestrator.syncIfAuthenticated()`
 * call can otherwise lose a ~12 ms race and incorrectly decide
 * "no credentials, skipping" while restoration is in flight (observed in
 * homebase.log on 2026-06-01).
 *
 * Returns the resolved [YouAuthState] (Authenticated, Unauthenticated,
 * Authenticating, or Error) on success, or `null` if the wait hit
 * [timeout]. A `null` return implies a wedged restoration; callers should
 * treat it as a "skip" rather than retrying without a bound.
 *
 * Extracted as a top-level function so it's unit-testable with virtual
 * time (`kotlinx.coroutines.test.runTest`) without constructing
 * `DriveSyncManager`, `AuthConnectionCoordinator`, and the rest of
 * `BackgroundSyncOrchestrator`'s heavy dependency graph.
 */
internal suspend fun awaitAuthRestored(
    authState: StateFlow<YouAuthState>,
    timeout: kotlin.time.Duration,
): YouAuthState? = withTimeoutOrNull(timeout) {
    authState.first { it !is YouAuthState.Initializing }
}
