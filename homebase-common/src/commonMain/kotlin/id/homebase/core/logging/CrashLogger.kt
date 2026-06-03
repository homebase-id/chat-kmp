package id.homebase.core.logging

import co.touchlab.kermit.Logger

/**
 * Logs crash information before app terminates
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    
    fun logCrash(thread: String, exception: Throwable) {
        try {
            Logger.e(tag = TAG) { "========== UNCAUGHT EXCEPTION ==========" }
            Logger.e(tag = TAG) { "Thread: $thread" }
            Logger.e(tag = TAG) { "Exception: ${exception::class.simpleName}" }
            Logger.e(tag = TAG) { "Message: ${exception.message}" }
            // Log the stack as text (not via the `throwable` parameter) so this
            // fatal isn't double-reported as a non-fatal by CrashlyticsLogWriter
            // — fatals are captured by the dedicated crash handlers instead.
            Logger.e(tag = TAG) { "Stack trace:\n${exception.stackTraceToString()}" }
            Logger.e(tag = TAG) { "========================================" }

            // TODO: Force flush logs to ensure they're written before crash
            // Note: Kermit's RollingFileLogWriter should flush automatically,
            // but this gives it a moment to complete
        } catch (e: Exception) {
            // If logging fails, at least try to print to console
            println("CrashLogger failed: ${e.message}")
            exception.printStackTrace()
        }
    }
}