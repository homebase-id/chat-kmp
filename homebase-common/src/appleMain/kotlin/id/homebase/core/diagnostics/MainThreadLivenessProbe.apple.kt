package id.homebase.core.diagnostics

/**
 * No dedicated-thread liveness probe on iOS — the coroutine loop's wall-clock-gap detection
 * ([detectWatchdogStarvation]) is dispatcher-agnostic and covers this platform without one.
 */
internal actual fun installMainThreadLivenessProbe() {
    // no-op
}
