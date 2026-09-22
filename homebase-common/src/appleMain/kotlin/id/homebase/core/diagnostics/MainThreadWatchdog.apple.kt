package id.homebase.core.diagnostics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_MONOTONIC
import platform.posix.RUSAGE_SELF
import platform.posix.clock_gettime
import platform.posix.getrusage
import platform.posix.rusage
import platform.posix.timespec
import platform.posix.timeval

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

/**
 * On Darwin (unlike Linux) `CLOCK_MONOTONIC` keeps counting while the device sleeps and while the
 * process is suspended, which is exactly the "continuous" clock this comparison needs.
 * `mach_continuous_time` is not exposed by the Kotlin/Native platform libraries.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun captureProcessTimes(): ProcessTimes? = memScoped {
    val usage = alloc<rusage>()
    if (getrusage(RUSAGE_SELF, usage.ptr) != 0) return@memScoped null
    val clock = alloc<timespec>()
    if (clock_gettime(CLOCK_MONOTONIC.convert(), clock.ptr) != 0) return@memScoped null
    ProcessTimes(
        cpuMs = usage.ru_utime.toMillis() + usage.ru_stime.toMillis(),
        continuousMs = clock.tv_sec * 1_000L + clock.tv_nsec / 1_000_000L,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun timeval.toMillis(): Long = tv_sec * 1_000L + tv_usec / 1_000L
