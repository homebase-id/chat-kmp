package id.homebase.core.logging

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.Foundation.NSThread

private const val TAG = "IOSCrashHandler"

/**
 * Installs the iOS crash handlers so fatal errors are written to `homebase.log`
 * (via Kermit / [CrashLogger]) before the process dies — matching the
 * `Thread.setDefaultUncaughtExceptionHandler` → [CrashLogger.logCrash] wiring
 * Android ([id.homebase.feed] `MainApplication`) and Desktop (`Main`) already use.
 *
 * iOS needs TWO independent handlers because no single hook sees both failure
 * channels:
 *
 *  1. **Objective-C `NSException`s** (e.g. a UIKit selector crash, or an ObjC
 *     framework throwing) — caught by [NSSetUncaughtExceptionHandler].
 *  2. **Kotlin/Native uncaught exceptions** (e.g. an `IllegalStateException`
 *     escaping a coroutine) — these do NOT raise an `NSException`; the K/N
 *     runtime aborts the process with `SIGABRT`, which bypasses the ObjC
 *     handler entirely. [setUnhandledExceptionHook] is the only thing that sees
 *     them.
 *
 * Previously only channel (1) was wired, so a Kotlin crash left nothing in
 * `homebase.log` — it surfaced only in Crashlytics' signal handler. Now both
 * channels log to the same file support reads, and both still let the process
 * crash normally so Crashlytics keeps capturing the native backtrace.
 *
 * Call once, on the main thread, at startup (see `MainViewController`).
 */
@OptIn(ExperimentalForeignApi::class)
fun setupIOSCrashHandler() {
    // (1) Objective-C exceptions.
    NSSetUncaughtExceptionHandler(staticCFunction { exception ->
        exception?.let {
            try {
                Logger.e(tag = TAG) { "========== UNCAUGHT NSEXCEPTION ==========" }
                Logger.e(tag = TAG) { "Name: ${it.name}" }
                Logger.e(tag = TAG) { "Reason: ${it.reason}" }
                Logger.e(tag = TAG) { "Call Stack: ${it.callStackSymbols}" }
                Logger.e(tag = TAG) { "==========================================" }

                // Give the file log writer a moment to flush before the process dies.
                NSThread.sleepForTimeInterval(0.1)
            } catch (e: Exception) {
                println("IOSCrashHandler (NSException) failed: ${e.message}")
            }
        }
    })

    // (2) Kotlin/Native uncaught exceptions. The hook runs on the crashing thread
    // just before termination. Mirrors the Android handler: log via CrashLogger,
    // then hand off so the app still crashes normally. `previousHook` is a pre-declared
    // var (captured by the lambda, read only at crash time) so there's no
    // accessed-before-initialized ambiguity around setUnhandledExceptionHook's return.
    var previousHook: ((Throwable) -> Unit)? = null
    previousHook = setUnhandledExceptionHook { throwable ->
        try {
            CrashLogger.logCrash(thread = "Kotlin/Native", exception = throwable)
            // Give the file log writer a moment to flush before the process dies.
            NSThread.sleepForTimeInterval(0.1)
        } catch (e: Throwable) {
            // If crash logging itself fails, fall back to console so we lose nothing silently.
            println("IOSCrashHandler (Kotlin) failed: ${e.message}")
        } finally {
            // Preserve normal crash behaviour — never swallow a fatal. Defer to any
            // previously-installed hook (none expected today); otherwise terminate so
            // the process aborts and Crashlytics' signal handler captures the backtrace.
            val prior = previousHook
            if (prior != null) prior(throwable) else terminateWithUnhandledException(throwable)
        }
    }
}
