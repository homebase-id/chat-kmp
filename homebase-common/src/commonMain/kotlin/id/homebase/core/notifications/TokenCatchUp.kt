package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import kotlin.coroutines.cancellation.CancellationException

// KMPNotifier doesn't replay a token to a listener that attaches after it was delivered (iOS before the first unlock).
internal suspend fun registerTokenDeliveredEarlier(getToken: suspend () -> String?, register: (String) -> Unit) {
    val token = try {
        getToken()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = "NotificationService") { "Token catch-up failed: ${e.message}" }
        null
    }
    if (token != null) register(token)
}
