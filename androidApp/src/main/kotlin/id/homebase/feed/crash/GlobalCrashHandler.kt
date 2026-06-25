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

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Preserve PR #737: a transient network blip that leaked to the global
                // handler must NOT crash the app or show the recovery screen.
                if (throwable.isTransientNetworkFailure()) {
                    runCatching { Firebase.crashlytics.recordException(throwable) }
                    Logger.w(tag = TAG) {
                        "Transient network failure on '${thread.name}'; not crashing: ${throwable.message}"
                    }
                    return@setDefaultUncaughtExceptionHandler
                }

                // Same class as the network case: ML Kit / MediaPipe background-removal runs on
                // native threads we don't own, so a teardown/callback failure can leak here that
                // no local try/catch could reach. Background removal is best-effort — degrade to
                // "no cutout", record a non-fatal, and keep the app alive.
                if (throwable.isMlKitTeardownFailure()) {
                    runCatching { Firebase.crashlytics.recordException(throwable) }
                    Logger.w(tag = TAG) {
                        "ML Kit/MediaPipe failure on '${thread.name}'; not crashing (background removal degrades to no cutout): ${throwable.message}"
                    }
                    return@setDefaultUncaughtExceptionHandler
                }

                CrashLogger.logCrash(thread.name, throwable)
                val reportPath = CrashReporting.writeReport(thread.name, throwable)
                // Record as a non-fatal: the captured default handler is Crashlytics',
                // and chaining to it would re-raise the OS dialog we're replacing.
                runCatching { Firebase.crashlytics.recordException(throwable) }

                if (shouldLaunchRecoveryScreen(currentProcessName(), reportPath?.toString())) {
                    launchCrashActivity(app, reportPath.toString())
                }
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
