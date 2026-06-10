package id.homebase.core.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Graphics
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * iOS implementation of [GpuTextDiagnostics] (see that class and BLANK_TEXT_INVESTIGATION.md).
 *
 * Reads the skiko global font-cache counters plus device physical memory, and logs a snapshot at the
 * moments the intermittent blank-text bug is known to strike: cold start, every foreground (with the
 * wall-clock time spent backgrounded), and every memory warning. The GPU glyph atlas itself isn't
 * readable (Compose owns the DirectContext), so these signals + the lifecycle events are the context
 * we correlate against a user-reported recurrence.
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
        return GpuTextDiagnostics.Snapshot(
            event = event,
            note = note,
            backgroundDurationMs = backgroundDurationMs,
            // skiko exposes these as Int on the native target; widen for the platform-neutral Snapshot.
            fontCacheUsedBytes = runCatching { Graphics.fontCacheUsed }.getOrNull()?.toLong(),
            fontCacheLimitBytes = runCatching { Graphics.fontCacheLimit }.getOrNull()?.toLong(),
            fontCacheCountUsed = runCatching { Graphics.fontCacheCountUsed }.getOrNull(),
            physicalMemoryMb = (NSProcessInfo.processInfo.physicalMemory / (1024uL * 1024uL)).toLong(),
        )
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

/**
 * iOS blank-text recovery EXPERIMENT — called from Swift (`ContentView`) when the user shakes the
 * device during a blank screen. Because every label is unreadable during the bug, a physical shake
 * is the only reliable trigger.
 *
 * It (1) logs the font-cache state to homebase.log BEFORE, (2) attempts a recovery — purge Skia's
 * CPU strike cache + GPU resource/atlas pages, then force a full re-composition so every `Text`
 * re-shapes and re-rasterizes against a clean atlas — and (3) logs the font-cache state again ~1.5s
 * later. The before/after `fontCacheCount` tells us whether the recovery worked (climbs to ~80 = text
 * rendered; stays ~0 = glyphs still aren't rasterizing, pointing upstream of the GPU).
 *
 * The CAMetalLayer fields come from Swift (Kotlin can't read Compose's Metal view) so the GPU-surface
 * state (device present? drawable size?) also lands in the shareable homebase.log. Runs on main.
 */
fun onBlankTextShake(
    metalLayerCount: Int,
    metalDevicePresent: Boolean,
    drawableWidth: Double,
    drawableHeight: Double,
) {
    val metal = "metal[count=$metalLayerCount device=$metalDevicePresent " +
        "drawable=${drawableWidth.toInt()}x${drawableHeight.toInt()}]"
    GpuTextDiagnostics.log(GpuTextDiagnostics.Event.ManualCapture, note = "shake/before $metal")

    runCatching { Graphics.purgeAllCaches() }
    TextRecovery.forceRecompose()

    MainScope().launch {
        delay(1500)
        GpuTextDiagnostics.log(GpuTextDiagnostics.Event.ManualCapture, note = "shake/after purge+recompose")
    }
}
