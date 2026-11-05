package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.UnauthorizedException
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Keeps this identity's shares current while the app runs, not only while Filesync is open: each time the app comes
 * online it seals circle keys to the people who have set up Filesync since they were shared with, and takes Write from
 * those whose last Edit share ended. At logout it signs Filesync out.
 */
class FilesyncShareUpkeep(
    private val sharing: FilesyncSharing,
    private val credentials: CredentialsManager,
    private val online: StateFlow<Boolean>,
    eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private var started = false
    private var lastRun: TimeSource.Monotonic.ValueTimeMark? = null

    init {
        scope.launch {
            eventBus.events.collect {
                if (it !is BackendEvent.SessionEnded) return@collect
                lastRun = null
                sharing.signOut()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { online.filter { it }.collect { upkeep() } }
    }

    internal suspend fun upkeep() {
        // A phone comes back online on every return to the foreground.
        if (lastRun?.let { it.elapsedNow() < MIN_INTERVAL } == true) return
        // Sharing was never turned on here.
        if (!FilesyncSession.restoreFor(credentials)) return
        lastRun = TimeSource.Monotonic.markNow()
        try {
            sharing.sealPending()
            sharing.syncEditors()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "share upkeep failed" }
            if (e is UnauthorizedException) sharing.signOut()
        }
    }

    private companion object {
        val MIN_INTERVAL = 5.minutes
        const val TAG = "FilesyncShareUpkeep"
    }
}
