package id.homebase.core.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.skia.Graphics
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * iOS implementation of [GpuTextDiagnostics] (see that class and BLANK_TEXT_INVESTIGATION.md).
 *
 * Slim validation logging for the (now-fixed) blank-text bug. Logs a font-cache snapshot at cold
 * start and on every foreground (with the wall-clock time spent backgrounded). The fix is the
 * prevention in `ContentView`: `ComposeView` is not built until the scene is first `.active`, so
 * Compose can never render its first frame while the app is backgrounded (the condition that
 * poisoned the GPU glyph atlas). This logging is how we confirm the fix keeps holding in the fleet —
 * a background-launched session (ColdStart with no immediate Foreground) must show
 * `fontCacheUsed=0 count=0` at its first Foreground, not the poisoned `6889/4` fingerprint.
 */
@OptIn(ExperimentalForeignApi::class)
private object IosGpuTextProbe : GpuTextDiagnostics.Probe {
    // Wall-clock (NSDate) rather than a monotonic clock on purpose: we want elapsed REAL time across
    // a suspended app, which a mach-based monotonic clock does not advance.
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
 * Wire up [GpuTextDiagnostics] on iOS. Call once, early — from `MainViewController.initializeApp()`.
 * Registers the probe, logs a `ColdStart` snapshot, and observes background/foreground so we log the
 * font-cache fingerprint at the moments relevant to the blank-text fix. Idempotent.
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

    Logger.i(tag = GpuTextDiagnostics.TAG) { "installed (probe + lifecycle observers attached)" }
    GpuTextDiagnostics.log(GpuTextDiagnostics.Event.ColdStart)
}

/**
 * Validation marker — called from Swift (`ContentView`) at the scene's FIRST `.active` phase, the
 * moment it builds `ComposeView` (creation is deferred until then so Compose can never render its
 * first frame while the app is backgrounded — the confirmed atlas-poisoning condition). Lands in
 * homebase.log so a session's timeline shows ColdStart → (gap, if launched in background) → this
 * line → a first Foreground that should now read `0/0` (fixed), not the poisoned `6889/4` fingerprint.
 */
fun logPrevention(note: String) {
    Logger.i(tag = GpuTextDiagnostics.TAG) { "prevention: $note" }
}
