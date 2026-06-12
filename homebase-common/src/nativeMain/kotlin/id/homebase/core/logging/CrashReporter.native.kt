package id.homebase.core.logging

import kotlin.experimental.ExperimentalNativeApi

actual fun crashlyticsLog(message: String) {
    CrashlyticsBridgeHolder.getBridge()?.log(message)
}

@OptIn(ExperimentalNativeApi::class)
actual fun crashlyticsRecordException(throwable: Throwable) {
    val bridge = CrashlyticsBridgeHolder.getBridge() ?: return
    bridge.recordException(
        name = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "KotlinException",
        reason = throwable.message ?: throwable.toString(),
        stackTrace = throwable.getStackTrace().toList(),
    )
}

/** Cap so a deep stack can't blow the on-device log buffer. */
internal const val FATAL_BREADCRUMB_STACK_LIMIT = 30

/**
 * Write the fatal exception's name/reason/stack into Crashlytics' **crash-safe
 * on-device log buffer** (a breadcrumb) so it is attached to the crash report.
 *
 * Why this exists separately from [crashlyticsRecordException]: `record(exceptionModel:)`
 * enqueues the non-fatal on Crashlytics' background serial queue and returns
 * immediately, so when a Kotlin/Native crash terminates the process microseconds
 * later that write usually loses the race and nothing reaches the dashboard. The
 * `log()` buffer, by contrast, is captured **synchronously by the SDK's signal
 * handler** at crash time — `terminateWithUnhandledException` raises SIGABRT, which
 * Crashlytics' handler turns into a fatal report that includes the recent log buffer.
 * So this readable context survives even when the async non-fatal does not.
 *
 * Call from the unhandled-exception hook just before terminating.
 */
@OptIn(ExperimentalNativeApi::class)
internal fun crashlyticsLogFatalBreadcrumb(throwable: Throwable) {
    val bridge = CrashlyticsBridgeHolder.getBridge() ?: return
    val name = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "KotlinException"
    val reason = throwable.message ?: throwable.toString()
    val breadcrumb = buildString {
        append("FATAL ").append(name)
        if (reason.isNotBlank()) append(": ").append(reason)
        throwable.getStackTrace().take(FATAL_BREADCRUMB_STACK_LIMIT).forEach { frame ->
            append('\n').append(frame)
        }
    }
    bridge.log(breadcrumb)
}
