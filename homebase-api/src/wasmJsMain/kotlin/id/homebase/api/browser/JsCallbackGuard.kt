package id.homebase.api.browser

import co.touchlab.kermit.Logger
import id.homebase.api.util.RepeatedErrorFilter
import kotlinx.coroutines.CancellationException

private const val TAG = "JsCallbackGuard"

private val reported = RepeatedErrorFilter()

/**
 * Runs [block] as the body of a browser callback (DOM event, media event, timer, worker message).
 *
 * A Kotlin exception cannot unwind across the Wasm/JS boundary: it traps the whole module
 * ("unreachable executed"), which kills whatever continuation the browser happened to be running.
 * When that is Compose's FlushCoroutineDispatcher, every later resumption on it throws
 * CoroutinesInternalError and no frame is ever scheduled again.
 */
fun guardJsCallback(tag: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        // Swallowing cancellation would break structured concurrency in the caller's scope.
        throw e
    } catch (t: Throwable) {
        if (reported.accept(tag)) {
            Logger.e(tag = TAG, throwable = t) { "$tag threw; only the first few are logged" }
        }
    }
}
