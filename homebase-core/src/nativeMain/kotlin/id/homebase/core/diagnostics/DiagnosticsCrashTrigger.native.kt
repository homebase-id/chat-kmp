package id.homebase.core.diagnostics

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.SIGSEGV
import platform.posix.raise

/**
 * iOS/native [DiagnosticsCrashTrigger]. Conservatively gated on [Platform.isDebugBinary]:
 * a debug framework shows the buttons; a release-optimised build (incl. the iOS dev
 * distribution) hides them — hiding on a dev build is an acceptable false negative, whereas
 * a crash button reaching production is not. Validate the iOS paths from a debug build, or
 * use the Android Dev build (where the `.dev` suffix gate is exact).
 */
@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
class IosDiagnosticsCrashTrigger : DiagnosticsCrashTrigger {

    override val enabled: Boolean = Platform.isDebugBinary

    override fun forceNativeCrash() {
        // Real signal → the SDK signal handler captures the native backtrace; mirrors the
        // SIGABRT a Kotlin/Native abort raises (see IOSCrashHandler).
        raise(SIGSEGV)
    }

    override fun forceRuntimeCrash() {
        // Unhandled Kotlin/Native exception → setUnhandledExceptionHook (IOSCrashHandler).
        throw RuntimeException("Forced runtime crash (diagnostics)")
    }
}
