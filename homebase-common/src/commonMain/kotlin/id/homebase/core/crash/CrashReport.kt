package id.homebase.core.crash

/** Pure rendering of a crash report (no I/O) so it is unit-testable. */
internal object CrashReport {
    fun render(
        metadata: CrashMetadata?,
        timeIso: String,
        thread: String,
        exceptionLine: String,
        stackText: String,
        logTail: String?,
    ): String = buildString {
        appendLine("===== Homebase crash report =====")
        appendLine("Time:      $timeIso")
        if (metadata != null) {
            appendLine("App:       ${metadata.appVersion} (${metadata.buildType}, built ${metadata.buildTime})")
            appendLine("Platform:  ${metadata.platform}")
            appendLine("Device:    ${metadata.device}")
        }
        appendLine("Thread:    $thread")
        appendLine("Exception: $exceptionLine")
        appendLine()
        appendLine("----- Stack trace -----")
        appendLine(stackText)
        if (logTail != null) {
            appendLine()
            appendLine("----- Recent log (tail) -----")
            appendLine(logTail)
        }
        appendLine("=================================")
    }
}
