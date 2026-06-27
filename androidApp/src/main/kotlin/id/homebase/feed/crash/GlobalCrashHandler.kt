package id.homebase.feed.crash

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import co.touchlab.kermit.Logger
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import id.homebase.api.client.isMlKitTeardownFailure
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.core.crash.CrashMetadata
import id.homebase.core.crash.CrashReporting
import id.homebase.core.logging.CrashLogger
import kotlinx.io.files.Path
import kotlin.system.exitProcess

/**
 * Installs CrashReporting + a global uncaught-exception handler that replaces the
 * OS "app keeps stopping" dialog with a separate-process [CrashActivity]. Install
 * EARLY in Application.onCreate (before Koin/DB) — it needs neither.
 */
object GlobalCrashHandler {
    private const val TAG = "GlobalCrashHandler"

    // CrashActivity runs in this dedicated process (see AndroidManifest android:process).
    // A crash *inside* it is the only real "loop" we must not relaunch the screen for.
    private const val CRASH_PROCESS_SUFFIX = ":crash"

    fun install(app: Application) {
        CrashReporting.install(
            metadata = CrashMetadata(
                appVersion = runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull() ?: "?",
                buildType = if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release",
                platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                buildTime = chat_kmp.homebase_common.BuildConfig.APP_BUILD_TIME,
            ),
            logDir = Path(app.filesDir.resolve("logs").absolutePath),
        )

        // Capture the handler currently installed so we can chain to it for FATAL recording.
        // After Firebase init (FirebaseInitProvider runs before Application.onCreate) this is
        // Crashlytics' uncaught handler, which writes the fatal to disk SYNCHRONOUSLY — it
        // survives process death and uploads next launch. A bare recordException() is only a
        // non-fatal and loses the flush race with our killProcess. Touch Crashlytics first so
        // the captured handler is really its.
        runCatching { Firebase.crashlytics }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Containable, non-fatal failures are handled BEFORE the terminating try/finally
            // below, so they return WITHOUT killing the process. The coroutine machinery
            // invokes this handler as a plain call — it does NOT unwind the Looper/thread — so
            // returning here lets the app keep running. This is PR #737's intent; the old
            // `finally { killProcess }` wrapped these returns and defeated it, so a dropped
            // connection or a TLS-inspecting VPN/proxy (an SSLHandshakeException on every call,
            // landing on a scope with no CoroutineExceptionHandler) could still silently kill an
            // already-authenticated app. See TlsInterceptionTest.
            if (isContainableNonFatal(throwable)) {
                runCatching { Firebase.crashlytics.recordException(throwable) }
                Logger.w(tag = TAG) {
                    val kind = if (throwable.isMlKitTeardownFailure()) {
                        "ML Kit/MediaPipe failure (background removal degrades to no cutout)"
                    } else {
                        "Transient network failure"
                    }
                    "$kind on '${thread.name}'; contained, not crashing: ${throwable.message}"
                }
                return@setDefaultUncaughtExceptionHandler
            }

            try {
                // Mark this run's death as a JVM crash we handled, so the next launch's
                // NativeCrashRecovery doesn't misclassify it as native. A native signal
                // crash never reaches this handler, so it never sets this marker.
                NativeCrashRecovery.markJvmCrashHandled(app)

                CrashLogger.logCrash(thread.name, throwable)
                val reportPath = CrashReporting.writeReport(thread.name, throwable)
                // Crash-safe breadcrumb with readable context, captured alongside the crash
                // even if the chained fatal below were to lose the race (mirrors the iOS
                // handler's log() breadcrumb).
                runCatching {
                    Firebase.crashlytics.log("FATAL on '${thread.name}': ${throwable.message}")
                }

                // Show our table-flip recovery screen first (separate :crash process — it
                // survives our death), then chain to the previously-installed handler
                // (Crashlytics') so this is recorded as a true FATAL, not a non-fatal that
                // never flushes. Trade-off: Crashlytics then delegates to the system handler,
                // which can briefly show the OS "app keeps stopping" dialog — validated on the
                // Dev build; revisit if it's intrusive.
                if (shouldLaunchRecoveryScreen(currentProcessName(), reportPath?.toString())) {
                    launchCrashActivity(app, reportPath.toString())
                }
                runCatching { previousHandler?.uncaughtException(thread, throwable) }
            } catch (t: Throwable) {
                runCatching { Logger.e(tag = TAG, throwable = t) { "Crash handler itself failed" } }
            } finally {
                // We own termination: kill our own process to suppress the OS dialog.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /**
     * A failure we contain (record + keep running) instead of terminating the process for:
     *  - a transient network blip — dropped socket, DNS, timeout, **or a TLS handshake
     *    failure**, including a TLS-inspecting VPN/proxy/AV presenting an untrusted cert; and
     *  - an ML Kit / MediaPipe teardown (best-effort background removal on native threads).
     *
     * Both routinely leak from a coroutine launched on a scope without its own
     * CoroutineExceptionHandler (e.g. a bare `viewModelScope.launch`). Killing the process for
     * them is the very thing PR #737 set out to avoid — and what the `finally` below used to do
     * anyway. Pure so it's unit-testable.
     */
    internal fun isContainableNonFatal(throwable: Throwable): Boolean =
        throwable.isTransientNetworkFailure() || throwable.isMlKitTeardownFailure()

    /**
     * Whether to launch the [CrashActivity] recovery screen for this crash. Pure so it
     * is unit-testable.
     *
     * We show the screen for **every main-process crash** — including a fast,
     * deterministic, repeatable one. The previous heuristic suppressed any crash that
     * happened < 10s after the last one, which silently swallowed exactly the worst
     * case: a reproducible launch crash (e.g. the note-to-self bootstrap firing before
     * credentials are ready) that crashes a few seconds into every relaunch. The user
     * saw a bare death with no recovery screen and no Share button.
     *
     * The only crash we must NOT relaunch the screen for is one happening *inside* the
     * `:crash` process itself — that means [CrashActivity] crashed, and relaunching it
     * would loop. A null [processName] can't prove we're that process, so we default to
     * showing (better a possible extra screen than a silent death). No [reportPath] →
     * nothing to show.
     */
    internal fun shouldLaunchRecoveryScreen(processName: String?, reportPath: String?): Boolean {
        if (reportPath == null) return false
        return processName?.endsWith(CRASH_PROCESS_SUFFIX) != true
    }

    /**
     * Name of the current process. [Application.getProcessName] is API 28+; below that we
     * read `/proc/self/cmdline` (the process name, NUL-padded). Returns null if unknown.
     */
    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull()
        } else {
            runCatching {
                java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
            }.getOrNull()
        }

    private fun launchCrashActivity(app: Application, reportPath: String) {
        val intent = Intent(app, CrashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(CrashActivity.EXTRA_REPORT_PATH, reportPath)
        }
        app.startActivity(intent)
    }
}
