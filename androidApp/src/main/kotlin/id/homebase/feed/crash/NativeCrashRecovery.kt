package id.homebase.feed.crash

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import co.touchlab.kermit.Logger
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import id.homebase.core.crash.CrashReporting
import kotlin.time.Instant
import kotlinx.io.files.Path

/**
 * Surfaces **native** crashes — NDK signals (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE) from
 * e.g. SQLCipher — that the JVM uncaught-exception handler in [GlobalCrashHandler] cannot
 * see. Such a crash kills the process via a signal, not a Java `Throwable`, so the JVM
 * handler never runs: no written report, no in-process recovery screen, and (before the
 * firebase-crashlytics-ndk dependency) not even a Crashlytics report.
 *
 * The NDK component installs Google's async-signal-safe handler, so a native crash is
 * recorded and reflected by [com.google.firebase.crashlytics.FirebaseCrashlytics.didCrashOnPreviousExecution].
 * We can't show a screen *during* a native crash (the signal kills us), so we detect it on
 * the **next** launch and show [CrashActivity] then.
 *
 * Telling a *native* crash apart from a *JVM* crash we already surfaced in-process:
 * [GlobalCrashHandler] writes [KEY_JVM_SURFACED] = true (synchronously, via commit()) for
 * every real JVM crash it handles. Native crashes never reach it, so they never set it.
 * Hence on the next launch:
 *
 *   previous run crashed (didCrashOnPreviousExecution) AND we did NOT handle it ourselves
 *   (no JVM marker)  ⇒  it was native — show recovery now.
 */
object NativeCrashRecovery {
    private const val TAG = "NativeCrashRecovery"
    internal const val PREFS = "crash_native_recovery"
    internal const val KEY_JVM_SURFACED = "jvm_crash_surfaced"

    // didCrashOnPreviousExecution() stays true for the whole process, and CrashActivity's
    // Restart reopens MainActivity in this same process.
    private var checkedThisProcess = false

    /**
     * Pure policy (unit-tested). Show the next-launch native recovery screen only when the
     * previous run crashed and [GlobalCrashHandler] did *not* already surface it — i.e. it
     * was a native signal, not a JVM exception we showed in-process.
     */
    internal fun shouldShowNativeCrashRecovery(
        didCrashOnPreviousExecution: Boolean,
        jvmCrashSurfacedLastRun: Boolean,
        alreadyCheckedThisProcess: Boolean,
    ): Boolean = !alreadyCheckedThisProcess && didCrashOnPreviousExecution && !jvmCrashSurfacedLastRun

    /**
     * Record that [GlobalCrashHandler] handled a JVM crash this run. Uses commit() (not
     * apply()) because the process is about to be killed — an async apply() write may not
     * reach disk first.
     */
    fun markJvmCrashHandled(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_JVM_SURFACED, true).commit()
        }
    }

    /**
     * At launch: if the previous run died in a native crash we couldn't surface in-process,
     * launch [CrashActivity] and return true (caller should finish() and skip normal
     * startup). Always clears the per-run JVM marker so it reflects only the current run.
     */
    fun checkAndMaybeLaunch(activity: Activity): Boolean {
        val alreadyChecked = checkedThisProcess
        checkedThisProcess = true
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val jvmSurfacedLastRun = prefs.getBoolean(KEY_JVM_SURFACED, false)
        // Reset for the current run (the value above reflects the previous run).
        runCatching { prefs.edit().putBoolean(KEY_JVM_SURFACED, false).commit() }

        val didCrash = runCatching {
            Firebase.crashlytics.didCrashOnPreviousExecution()
        }.getOrDefault(false)

        if (!shouldShowNativeCrashRecovery(didCrash, jvmSurfacedLastRun, alreadyChecked)) return false

        Logger.w(tag = TAG) {
            "Previous run ended in a native crash not surfaced in-process; showing recovery."
        }
        val report = runCatching { writeNativeReport(activity) }.getOrNull()
        return runCatching {
            activity.startActivity(
                Intent(activity, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    report?.let { putExtra(CrashActivity.EXTRA_REPORT_PATH, it.toString()) }
                }
            )
            true
        }.getOrDefault(false)
    }

    private fun writeNativeReport(context: Context): Path? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val exit = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE } ?: return null
        // Android 12+ hands over the tombstone (a protobuf); Android 11 gives no trace at all.
        val frames = exit.traceInputStream?.use { tombstoneStrings(it.readBytes()) }.orEmpty()
        return CrashReporting.writeReportRaw(
            thread = "native",
            name = "Native crash",
            reason = "pid=${exit.pid} at=${Instant.fromEpochMilliseconds(exit.timestamp)} ${exit.description.orEmpty()}".trim(),
            stack = frames.ifEmpty { listOf("(no tombstone on Android ${Build.VERSION.RELEASE}; adb shell dumpsys dropbox --print SYSTEM_TOMBSTONE)") },
        )
    }

    /** The readable runs of a protobuf tombstone: abort message, frame symbols and library paths. */
    internal fun tombstoneStrings(bytes: ByteArray, minLength: Int = 6, maxLines: Int = 300): List<String> {
        val out = mutableListOf<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= minLength && out.size < maxLines) out += run.toString()
            run.clear()
        }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) run.append(c.toChar()) else flush()
        }
        flush()
        return out
    }
}
