package id.homebase.core.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.skia.Graphics
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSProcessInfoThermalStateCritical
import platform.Foundation.NSProcessInfoThermalStateFair
import platform.Foundation.NSProcessInfoThermalStateNominal
import platform.Foundation.NSProcessInfoThermalStateSerious
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * iOS implementation of [GpuTextDiagnostics] (see that class and BLANK_TEXT_INVESTIGATION.md).
 *
 * Reads the skiko global font-cache counters plus NSProcessInfo memory/thermal signals, and logs a
 * snapshot at the moments the intermittent blank-text bug is known to strike: cold start, every
 * foreground (with the wall-clock time spent backgrounded), and every memory warning. The GPU glyph
 * atlas itself isn't readable (Compose owns the DirectContext), so these signals are the context we
 * correlate against a user-reported recurrence.
 */
@OptIn(ExperimentalForeignApi::class)
private object IosGpuTextProbe : GpuTextDiagnostics.Probe {
    // Wall-clock (NSDate) rather than a monotonic clock on purpose: we want elapsed REAL time across
    // a suspended app ("idle a long time"), which a mach-based monotonic clock does not advance.
    private var backgroundedAtEpoch: Double? = null

    fun markBackgrounded() {
        backgroundedAtEpoch = NSDate().timeIntervalSince1970
    }

    fun consumeBackgroundDurationMs(): Long? {
        val started = backgroundedAtEpoch ?: return null
        backgroundedAtEpoch = null
        return ((NSDate().timeIntervalSince1970 - started) * 1000.0).toLong()
    }

    override fun snapshot(
        event: GpuTextDiagnostics.Event,
        note: String?,
        backgroundDurationMs: Long?,
    ): GpuTextDiagnostics.Snapshot {
        val processInfo = NSProcessInfo.processInfo
        return GpuTextDiagnostics.Snapshot(
            event = event,
            note = note,
            backgroundDurationMs = backgroundDurationMs,
            fontCacheUsedBytes = runCatching { Graphics.fontCacheUsed }.getOrNull(),
            fontCacheLimitBytes = runCatching { Graphics.fontCacheLimit }.getOrNull(),
            fontCacheCountUsed = runCatching { Graphics.fontCacheCountUsed }.getOrNull(),
            thermalState = thermalName(processInfo.thermalState),
            lowPowerMode = processInfo.lowPowerModeEnabled,
            physicalMemoryMb = (processInfo.physicalMemory / (1024uL * 1024uL)).toLong(),
        )
    }

    private fun thermalName(state: NSProcessInfoThermalState): String = when (state) {
        NSProcessInfoThermalStateNominal -> "nominal"
        NSProcessInfoThermalStateFair -> "fair"
        NSProcessInfoThermalStateSerious -> "serious"
        NSProcessInfoThermalStateCritical -> "critical"
        else -> "unknown"
    }
}

private var installed = false

/**
 * Wire up [GpuTextDiagnostics] on iOS. Call once, early — from
 * `MainViewController.initializeApp()`. Registers the probe, logs a `ColdStart` snapshot, and
 * observes background / foreground / memory-warning so we log at the moments the blank-text bug
 * strikes. Idempotent.
 */
@OptIn(ExperimentalForeignApi::class)
fun installGpuTextDiagnostics() {
    if (installed) return
    installed = true

    GpuTextDiagnostics.register(IosGpuTextProbe)

    val center = NSNotificationCenter.defaultCenter
    val mainQueue = NSOperationQueue.mainQueue

    center.addObserverForName(
        name = UIApplicationDidEnterBackgroundNotification,
        `object` = null,
        queue = mainQueue,
    ) { _ -> IosGpuTextProbe.markBackgrounded() }

    center.addObserverForName(
        name = UIApplicationWillEnterForegroundNotification,
        `object` = null,
        queue = mainQueue,
    ) { _ ->
        GpuTextDiagnostics.log(
            GpuTextDiagnostics.Event.Foreground,
            backgroundDurationMs = IosGpuTextProbe.consumeBackgroundDurationMs(),
        )
    }

    center.addObserverForName(
        name = UIApplicationDidReceiveMemoryWarningNotification,
        `object` = null,
        queue = mainQueue,
    ) { _ -> GpuTextDiagnostics.log(GpuTextDiagnostics.Event.MemoryWarning) }

    Logger.i(tag = GpuTextDiagnostics.TAG) { "installed (probe + lifecycle observers attached)" }
    GpuTextDiagnostics.log(GpuTextDiagnostics.Event.ColdStart)
}
