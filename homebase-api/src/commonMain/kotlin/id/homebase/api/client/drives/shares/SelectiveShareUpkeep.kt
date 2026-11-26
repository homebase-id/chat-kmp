package id.homebase.api.client.drives.shares

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.youauth.AppSignIn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Keeps an app's shares current while this app runs, not only while its screen is open: each time the app comes online
 * it runs [steps] (sealing keys to the people who have set up the app since, taking Write from those whose last
 * writing share ended), each on its own. At logout it signs the app out ([signOut]).
 */
open class SelectiveShareUpkeep(
    private val tag: String,
    private val session: AppSignIn,
    private val credentials: CredentialsManager,
    private val online: StateFlow<Boolean>,
    eventBus: EventBus,
    private val scope: CoroutineScope,
    signOut: suspend () -> Unit,
    private val signOutIfRefused: suspend (Throwable) -> Boolean,
    private val steps: List<Pair<String, suspend () -> Unit>>,
) {
    private var started = false
    private var lastRun: TimeSource.Monotonic.ValueTimeMark? = null

    init {
        scope.launch {
            eventBus.events.collect {
                if (it !is BackendEvent.SessionEnded) return@collect
                lastRun = null
                signOut()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { online.filter { it }.collect { upkeep() } }
    }

    suspend fun upkeep() {
        // A phone comes back online on every return to the foreground.
        if (lastRun?.let { it.elapsedNow() < MIN_INTERVAL } == true) return
        // Sharing was never turned on here.
        if (step("restoring the sign-in") { session.restoreFor(credentials) } != true) return
        lastRun = TimeSource.Monotonic.markNow()
        steps.forEach { (what, block) -> step(what) { block() } }
    }

    // A failed step mustn't end the collector, nor keep the others from running.
    private suspend fun <T> step(what: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, tag) { "$what failed" }
            signOutIfRefused(e)
            null
        }

    private companion object {
        val MIN_INTERVAL = 5.minutes
    }
}
