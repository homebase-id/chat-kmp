package id.homebase.api.coroutines

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

const val COROUTINE_CRASH_TAG = "CoroutineCrash"

/**
 * Shared fallback [CoroutineExceptionHandler] for app-created scopes.
 *
 * A coroutine that fails on a scope WITHOUT a handler routes its exception to
 * the thread's uncaught handler and kills the process. The recurring shape of
 * that crash here is a transient `UnknownHostException` (dropped network /
 * unreachable identity host) escaping an outbound Ktor call — a condition we
 * never want to be fatal. This handler turns it into a logged, non-fatal event:
 * the scope's other children keep running.
 *
 * It logs via Kermit WITH the throwable, which — through the installed
 * `CrashlyticsLogWriter` — both writes the stack to `homebase.log` AND records a
 * Crashlytics non-fatal. Crucially, the async stack of a suspended network call
 * has NO app frames (the caller already suspended), so [scopeLabel] and any
 * [CoroutineName] on the failing coroutine are what actually attribute the
 * failure to a subsystem in the dashboard. Name your scopes.
 */
fun loggingExceptionHandler(scopeLabel: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { context, throwable ->
        val coroutineName = context[CoroutineName]?.name
        Logger.e(throwable = throwable, tag = COROUTINE_CRASH_TAG) {
            buildString {
                append("Uncaught coroutine exception isolated by fallback handler (scope='")
                append(scopeLabel).append('\'')
                if (coroutineName != null) append(" coroutine='").append(coroutineName).append('\'')
                append("). Not fatal — the scope's other children keep running.")
            }
        }
    }

/**
 * Creates a [CoroutineScope] with the standard app shape: a [SupervisorJob] (one
 * child's failure doesn't cancel its siblings), a [CoroutineName] for crash
 * attribution, and the shared [loggingExceptionHandler] so a missing local
 * handler can't escalate a transient failure into a process kill.
 *
 * Prefer this over a bare `CoroutineScope(SupervisorJob() + dispatcher)` for any
 * long-lived, app-owned background scope.
 */
fun supervisedScope(
    label: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcher + CoroutineName(label) + loggingExceptionHandler(label))
