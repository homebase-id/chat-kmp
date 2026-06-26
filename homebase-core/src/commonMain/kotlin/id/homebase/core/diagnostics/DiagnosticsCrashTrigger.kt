package id.homebase.core.diagnostics

/**
 * Dev-only hook to deliberately crash the app so crash capture + recovery can be validated
 * on a real build (does the recovery screen show? does the crash reach Crashlytics?).
 *
 * [enabled] MUST be false on a production release — the UI only offers these actions when
 * it is true, so a "crash the app" button can never reach end users. Each platform decides
 * conservatively (a false negative just hides the buttons; a false positive ships a crash
 * button, which is unacceptable).
 */
interface DiagnosticsCrashTrigger {
    /** True only on a non-production build (dev/debug). Production returns false. */
    val enabled: Boolean

    /**
     * Raise a native crash (SIGSEGV) in this process — exercises the native signal path
     * (Crashlytics-NDK on Android, the signal handler on iOS) plus the next-launch native
     * recovery. Does not return.
     */
    fun forceNativeCrash()

    /**
     * Throw an uncaught runtime exception — exercises the managed uncaught-exception
     * handler (Android `Thread.setDefaultUncaughtExceptionHandler`, iOS
     * `setUnhandledExceptionHook`) and the fatal → Crashlytics path.
     */
    fun forceRuntimeCrash()
}

/** Inert trigger for platforms/builds without diagnostics (desktop, web, production). */
object NoOpDiagnosticsCrashTrigger : DiagnosticsCrashTrigger {
    override val enabled: Boolean = false
    override fun forceNativeCrash() {}
    override fun forceRuntimeCrash() {}
}
