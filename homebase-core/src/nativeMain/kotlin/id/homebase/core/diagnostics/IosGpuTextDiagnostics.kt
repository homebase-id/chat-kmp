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

    // Session high-water mark of the font cache. The cache builds during USE (between lifecycle
    // events), so a periodic sampler updates this; it's the gate that proves text really rendered
    // this session (so the blank-text trigger doesn't fire on a fresh cold start). Main-thread only.
    var peakFontCacheUsedBytes: Long = 0L
        private set

    fun currentFontCacheUsedBytes(): Long =
        runCatching { Graphics.fontCacheUsed }.getOrNull()?.toLong() ?: 0L

    fun samplePeak() {
        val used = currentFontCacheUsedBytes()
        if (used > peakFontCacheUsedBytes) peakFontCacheUsedBytes = used
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
        maybeFireBlankTextTrigger()
    }

    center.addObserverForName(
        name = UIApplicationDidReceiveMemoryWarningNotification,
        `object` = null,
        queue = mainQueue,
    ) { _ -> GpuTextDiagnostics.log(GpuTextDiagnostics.Event.MemoryWarning) }

    // Periodic peak sampler: the cache builds during USE (between lifecycle events), so sample it so
    // we know text really rendered this session — the gate that stops the trigger firing on cold
    // starts. Cheap (reads one global counter). iOS suspends this loop while backgrounded.
    MainScope().launch {
        while (true) {
            IosGpuTextProbe.samplePeak()
            delay(PEAK_SAMPLE_INTERVAL_MS)
        }
    }

    Logger.i(tag = GpuTextDiagnostics.TAG) { "installed (probe + lifecycle observers attached)" }
    GpuTextDiagnostics.log(GpuTextDiagnostics.Event.ColdStart)
}

// --- Blank-text auto-trigger (observe-only hypothesis; see BlankTextTrigger) ---

private const val TRIGGER_PEAK_RENDERED_BYTES = 200_000L // text rendered substantially this session
private const val TRIGGER_BLANK_BYTES = 10_000L          // font cache now near-empty (reclaimed)
private const val PEAK_SAMPLE_INTERVAL_MS = 5_000L

/**
 * Blank-text auto-trigger HYPOTHESIS, evaluated on every foreground. If the font cache is near-empty
 * yet it was substantial earlier this session, iOS likely reclaimed the GPU/font resources while
 * backgrounded — the condition that precedes blank text. OBSERVE-ONLY: fire a visible toast + log so
 * TestFlight can measure the false-positive rate. No recovery yet. Thresholds are starting
 * hypotheses (blank foregrounds measured ~6.9KB used; healthy ~1.9MB) — tune from the logged values.
 */
private fun maybeFireBlankTextTrigger() {
    val used = IosGpuTextProbe.currentFontCacheUsedBytes()
    val peak = IosGpuTextProbe.peakFontCacheUsedBytes
    if (peak > TRIGGER_PEAK_RENDERED_BYTES && used < TRIGGER_BLANK_BYTES) {
        Logger.i(tag = GpuTextDiagnostics.TAG) {
            "TRIGGER MET (observe-only): foreground font cache reclaimed — used=$used peak=$peak " +
                "(rule: used<$TRIGGER_BLANK_BYTES AND peak>$TRIGGER_PEAK_RENDERED_BYTES) — firing toast"
        }
        BlankTextTrigger.fire(used, peak)
    }
}

/**
 * Shake recovery v2 — SURFACE RECREATION. Logging side of the experiment; the recovery itself is
 * Swift-side (`ContentView` bumps `.id()` on `ComposeView`, tearing down the `ComposeUIViewController`
 * and rebuilding it → fresh skiko `GrDirectContext` → fresh glyph atlas with empty residency
 * bookkeeping → every glyph is a miss → fresh rasterize + upload → text renders).
 *
 * Why not purge+recompose (v1)? Field-falsified three times (2026-06-10, 2026-06-12 ×2): even
 * freshly recomposed text with freshly rasterized CPU strikes stayed blank, because the per-context
 * glyph-atlas texture/bookkeeping lives on the `GrDirectContext` Compose owns internally — no public
 * API reaches it. The atlas is born dead when Compose's first frame renders while the app is still
 * in the BACKGROUND state (iOS rejects background Metal submissions): fingerprint
 * `fontCacheUsed=6889 count=4` at a first-ever Foreground with no prior background mark.
 *
 * Swift calls this twice per shake — `phase="before"` ahead of the recreation and
 * `phase="after-recreate"` ~2.5s after — each time with the CAMetalLayer state read from the
 * (old/new) view hierarchy, so the GPU-surface state lands in the shareable homebase.log.
 * Text visibly returning + a healthy after-state = recovery proven; we then hang it on the
 * (recalibrated) auto-trigger.
 */
fun logShakeRecovery(
    phase: String,
    metalLayerCount: Int,
    metalDevicePresent: Boolean,
    drawableWidth: Double,
    drawableHeight: Double,
) {
    val metal = "metal[count=$metalLayerCount device=$metalDevicePresent " +
        "drawable=${drawableWidth.toInt()}x${drawableHeight.toInt()}]"
    GpuTextDiagnostics.log(GpuTextDiagnostics.Event.ManualCapture, note = "shake-recreate/$phase $metal")
}
