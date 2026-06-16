package id.homebase.feed.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import co.touchlab.kermit.Logger
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
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
    private const val CRASH_LOOP_PREFS = "crash_loop_guard"
    private const val KEY_LAST_CRASH_MS = "last_crash_ms"
    private const val CRASH_LOOP_WINDOW_MS = 10_000L

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

                CrashLogger.logCrash(thread.name, throwable)
                val reportPath = CrashReporting.writeReport(thread.name, throwable)
                // Record as a non-fatal: the captured default handler is Crashlytics',
                // and chaining to it would re-raise the OS dialog we're replacing.
                runCatching { Firebase.crashlytics.recordException(throwable) }

                if (!isCrashLooping(app) && reportPath != null) {
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

    /** True if another crash happened < 10s ago (likely CrashActivity itself crashed). */
    private fun isCrashLooping(app: Application): Boolean {
        val prefs = app.getSharedPreferences(CRASH_LOOP_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CRASH_MS, 0L)
        prefs.edit().putLong(KEY_LAST_CRASH_MS, now).apply()
        return now - last < CRASH_LOOP_WINDOW_MS
    }

    private fun launchCrashActivity(app: Application, reportPath: String) {
        val intent = Intent(app, CrashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(CrashActivity.EXTRA_REPORT_PATH, reportPath)
        }
        app.startActivity(intent)
    }
}
