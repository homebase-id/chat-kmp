package id.homebase.feed

import co.touchlab.kermit.Logger
import com.google.firebase.crashlytics.FirebaseCrashlytics
import id.homebase.api.coroutines.COROUTINE_CRASH_TAG
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext

/**
 * Global, ServiceLoader-registered catch-all for coroutines launched on a scope
 * with NO local [CoroutineExceptionHandler] (e.g. a `viewModelScope`, or any
 * scope we haven't given the shared `loggingExceptionHandler`). kotlinx invokes
 * every ServiceLoader-registered handler for those cases.
 *
 * IMPORTANT: a global handler CANNOT stop the process kill — after it runs,
 * kotlinx still forwards the exception to the thread's uncaught handler. Its sole
 * job is to make the otherwise unattributable crash report useful: a suspended
 * network call's stack is pure `okhttp3` with no app frames, so we stamp
 * Crashlytics custom keys with the offending coroutine name + thread (set just
 * before the fatal is recorded, so they land on that report) and log the stack.
 *
 * Scopes that carry the shared `loggingExceptionHandler` never reach here — a
 * handler present in the coroutine's own context short-circuits the global path.
 *
 * Wiring: registered via
 * `META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler` and kept from
 * R8 by a `-keep` rule in `proguard-rules.pro` (ServiceLoader instantiates it by
 * fully-qualified name, so it must not be renamed or stripped).
 */
class GlobalCoroutineExceptionHandler : CoroutineExceptionHandler {
    override val key = CoroutineExceptionHandler

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        val coroutineName = context[CoroutineName]?.name ?: "unnamed"
        val thread = Thread.currentThread().name

        try {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("uncaught_coroutine_scope", coroutineName)
                setCustomKey("uncaught_coroutine_thread", thread)
            }
        } catch (_: Throwable) {
            // Crashlytics not initialized / collection disabled — reporting must
            // never itself throw out of an exception handler.
        }

        // Log the stack as TEXT (not via the `throwable` parameter) so this is
        // not also recorded as a Crashlytics non-fatal by CrashlyticsLogWriter —
        // the fatal is captured by Firebase's native uncaught handler, and the
        // custom keys above attribute it. Same rationale as CrashLogger.
        Logger.e(tag = COROUTINE_CRASH_TAG) {
            "Uncaught coroutine exception (no local handler) coroutine='$coroutineName' " +
                "thread='$thread' — process will terminate; custom keys " +
                "uncaught_coroutine_scope/_thread are stamped on the fatal report.\n" +
                exception.stackTraceToString()
        }
    }
}
