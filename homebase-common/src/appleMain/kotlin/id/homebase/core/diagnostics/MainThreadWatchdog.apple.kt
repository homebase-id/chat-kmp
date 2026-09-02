package id.homebase.core.diagnostics

/**
 * iOS stack capture for [MainThreadWatchdog].
 *
 * Kotlin/Native has no public API to capture another thread's backtrace from the watchdog thread
 * (it would require signal handlers + `thread_get_state` symbolication), so we return `null`. The
 * watchdog still logs that the main thread stalled and for how long, which is the breadcrumb we
 * were missing; iOS additionally has its own OS-level main-thread watchdog (0x8badf00d) for
 * launch/resume hangs.
 */
internal actual fun captureMainThreadStackTrace(maxFrames: Int): String? = null
