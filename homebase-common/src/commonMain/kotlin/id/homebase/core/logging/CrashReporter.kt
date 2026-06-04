package id.homebase.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/**
 * Forwards a breadcrumb [message] to the platform crash reporter (Firebase
 * Crashlytics on Android/iOS). These messages are attached to the next crash
 * report so the dashboard stack carries the recent log trail leading up to a
 * crash. No-op on Desktop/Web, where no crash backend is wired.
 *
 * The Crashlytics SDK gates upload on its own collection flag (see
 * [setErrorCollectionEnabled]), so callers don't need to re-check the user's
 * error-collection preference here.
 */
expect fun crashlyticsLog(message: String)

/**
 * Records a non-fatal [throwable] to the platform crash reporter so handled
 * exceptions (caught, logged, recovered-from) surface in the dashboard with a
 * full stack — not just as breadcrumb text. No-op on Desktop/Web.
 */
expect fun crashlyticsRecordException(throwable: Throwable)

/**
 * Kermit [LogWriter] that mirrors app logs into the platform crash reporter:
 *
 *  - every log at [minBreadcrumbSeverity] or above becomes a Crashlytics
 *    breadcrumb ([crashlyticsLog]) — this is the "recent log trail" attached to
 *    the next crash report.
 *  - every log at [minRecordSeverity] or above that carries a [Throwable] is
 *    also recorded as a non-fatal ([crashlyticsRecordException]), so handled
 *    exceptions surface in the dashboard instead of vanishing into the log file.
 *
 * Fatal/uncaught crashes are captured by the dedicated crash handlers
 * (Firebase's native uncaught handler on Android, [setupIOSCrashHandler] on
 * iOS), NOT by this writer — [CrashLogger] deliberately logs the fatal stack as
 * text (no `throwable` parameter) so it is not double-reported here as a
 * non-fatal.
 */
class CrashlyticsLogWriter(
    private val minBreadcrumbSeverity: Severity = Severity.Info,
    private val minRecordSeverity: Severity = Severity.Error,
) : LogWriter() {
    override fun isLoggable(tag: String, severity: Severity): Boolean =
        severity >= minBreadcrumbSeverity

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        crashlyticsLog("${severity.name.first()}/$tag: $message")
        if (throwable != null && severity >= minRecordSeverity) {
            crashlyticsRecordException(throwable)
        }
    }
}