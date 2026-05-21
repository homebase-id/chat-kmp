package id.homebase.core.diagnostics

import android.os.Looper

/**
 * Android stack capture for [MainThreadWatchdog]: snapshots the main `Looper` thread.
 *
 * The watchdog loop itself now lives in commonMain; this only provides the platform-specific
 * stack grab. Captured ~1s before the OS ANR cutoff so the frames are usable
 * (deobfuscated-on-mapping) instead of the input-dispatcher-level R8 frames the system crash dump
 * lands after the kill.
 */
internal actual fun captureMainThreadStackTrace(maxFrames: Int): String? {
    val stack = Looper.getMainLooper().thread.stackTrace
    return buildString {
        stack.take(maxFrames).forEach { appendLine("    at $it") }
    }
}
