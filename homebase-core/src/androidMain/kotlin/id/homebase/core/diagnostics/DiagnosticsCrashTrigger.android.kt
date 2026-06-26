package id.homebase.core.diagnostics

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlin.concurrent.thread

/**
 * Android [DiagnosticsCrashTrigger]. Gated on the application id suffix so it is present on
 * the `.dev` and `.debug` builds (which carry the suffix) but compiled-effectively-off in
 * the production `release` build (no suffix). This is a reliable, conservative gate — far
 * safer than `BuildConfig.DEBUG`, which is false for the minified `dev` build we validate.
 */
class AndroidDiagnosticsCrashTrigger(private val context: Context) : DiagnosticsCrashTrigger {

    override val enabled: Boolean =
        context.packageName.endsWith(".dev") || context.packageName.endsWith(".debug")

    override fun forceNativeCrash() {
        // A real SIGSEGV delivered to our own process — caught by the Crashlytics-NDK
        // signal handler exactly like a SQLCipher native crash would be.
        Os.kill(Os.getpid(), OsConstants.SIGSEGV)
    }

    override fun forceRuntimeCrash() {
        // Uncaught on a fresh thread so it reaches Thread.setDefaultUncaughtExceptionHandler
        // (GlobalCrashHandler), not a coroutine handler.
        thread(name = "diagnostics-forced-crash") {
            throw RuntimeException("Forced runtime crash (diagnostics)")
        }
    }
}
