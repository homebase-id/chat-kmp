package id.homebase.core.diagnostics

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Throttled logging gate shared by every stall detector feeding [MainThreadWatchdog].
 *
 * More than one detector can observe the same incident independently (the coroutine loop's
 * own wall-clock-gap check, and — on Android/JVM — the [MainThreadLivenessProbe] running on a
 * dedicated OS thread). Both call [reportIfDue] concurrently; whichever gets there first for a
 * given [throttleMs] window logs, the other's call is a cheap no-op. This is what prevents the
 * same freeze from producing two log lines.
 */
internal class StallReporter(
    private val throttleMs: Long,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
) {
    private val lock = SynchronizedObject()
    private var lastReportAtMs: Long? = null

    fun reportIfDue(render: () -> String) {
        val shouldLog = synchronized(lock) {
            val now = nowMs()
            val last = lastReportAtMs
            if (last != null && now - last < throttleMs) {
                false
            } else {
                lastReportAtMs = now
                true
            }
        }
        if (shouldLog) log(render())
    }
}
