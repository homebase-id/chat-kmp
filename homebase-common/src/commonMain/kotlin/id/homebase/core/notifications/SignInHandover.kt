package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.ApiCredentials
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/** What moving push from a replaced sign-in to the current one needs; the server calls behind [handOverPush]. */
internal interface PushHandoverSteps {
    /** False where the platform has no push at all (desktop). */
    val hasPush: Boolean
    /** This device's push token now, or null when it has none (web push, or none yet). */
    suspend fun pushToken(): String?
    suspend fun subscribeCurrent(token: String)
    suspend fun unsubscribeCurrent()
    /** Succeeds when [replaced] has no subscription (404) too. */
    suspend fun unsubscribeReplaced(replaced: ApiCredentials)
    suspend fun deleteReplaced(replaced: ApiCredentials)
}

/**
 * After signing in again, push moves to the current sign-in and the replaced one is deleted. It either all
 * happens or push stays on the replaced sign-in: never two subscriptions (every push twice), never none.
 */
internal suspend fun handOverPush(replaced: ApiCredentials, steps: PushHandoverSteps, retryDelayMs: Long = 2_000) {
    suspend fun attempt(what: String, block: suspend () -> Unit): Boolean {
        repeat(RETRIES) { n ->
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(throwable = e, tag = TAG) { "Push hand-over: $what failed (${n + 1}/$RETRIES)" }
                if (n < RETRIES - 1) delay(retryDelayMs shl n)
            }
        }
        return false
    }

    if (steps.hasPush) {
        // Web push lives with the sign-in it was made for, and nothing here can move it: the replaced one stays.
        val token = steps.pushToken() ?: return
        if (!attempt("subscribing this sign-in") { steps.subscribeCurrent(token) }) return
        if (!attempt("unsubscribing the replaced sign-in") { steps.unsubscribeReplaced(replaced) }) {
            attempt("undoing this sign-in's subscription") { steps.unsubscribeCurrent() }
            return
        }
    }
    // Without push left on it, a sign-in that outlives this is only a stale registration.
    attempt("deleting the replaced sign-in") { steps.deleteReplaced(replaced) }
}

private const val RETRIES = 3
private const val TAG = "NotificationService"
