package id.homebase.core.diagnostics

/** Why a [StallEvent] fired. */
internal enum class StallKind { MainThreadBlock, WatchdogStarved }

/** Which detector observed the [StallEvent] — lets the log line show whether the dedicated
 *  OS-thread probe corroborated (or beat) the coroutine loop's own detection. */
internal enum class StallSource { CoroutineLoop, DedicatedThread }

internal data class StallEvent(
    val kind: StallKind,
    val source: StallSource,
    val observedMs: Long,
    val memory: MemoryDiagnostics.Snapshot? = null,
)

/**
 * Given the gap this loop iteration expected to sleep for ([expectedGapMs], i.e.
 * `tickIntervalMs`) and the wall-clock gap it actually observed ([actualGapMs]), decides whether
 * the watchdog's own scheduling was delayed enough to call it starved. Returns the observed
 * stall duration, or `null` if the gap is unremarkable.
 */
internal fun detectWatchdogStarvation(expectedGapMs: Long, actualGapMs: Long, slackFactor: Double = 3.0): Long? {
    val threshold = (expectedGapMs * slackFactor).toLong()
    return if (actualGapMs > threshold) actualGapMs else null
}

/** Pure, deterministic message renderer — unit-tested without any coroutine/platform. */
internal fun renderStallMessage(event: StallEvent, stack: String?): String = buildString {
    val prefix = if (event.source == StallSource.DedicatedThread) "[dedicated-thread probe] " else ""
    when (event.kind) {
        StallKind.MainThreadBlock -> {
            appendLine(
                "${prefix}Main/UI thread stalled >${event.observedMs}ms — likely a recomposition " +
                    "loop or blocking I/O on the UI dispatcher."
            )
            if (stack != null) {
                appendLine("UI thread stack at sample:")
                append(stack)
            } else {
                appendLine("(UI thread stack capture not supported on this platform)")
            }
        }

        StallKind.WatchdogStarved -> {
            appendLine(
                "${prefix}Process/dispatchers stalled ~${event.observedMs}ms (watchdog starved) — " +
                    "the watchdog's own loop was delayed; likely Dispatchers.Default pool " +
                    "exhaustion or an OS-level freeze."
            )
            if (stack != null) {
                appendLine("UI thread stack at recovery:")
                append(stack)
            } else {
                appendLine("(UI thread stack capture not supported on this platform)")
            }
        }
    }
    event.memory?.let { appendLine("Memory at stall: ${MemoryDiagnostics.format(it)}") }
}
