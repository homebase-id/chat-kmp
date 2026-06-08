package id.homebase.core.diagnostics

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile

/**
 * Field instrumentation for the intermittent **iOS "all text blank, frames fine"** bug.
 *
 * Background: on iOS (Metal/Skia) the app occasionally comes up with every text label blank while
 * frames, icons and images render normally — the signature of a stale GPU glyph atlas (already-drawn
 * text samples evicted/never-populated atlas slots; freshly-drawn glyphs rasterise fine). It is
 * intermittent and triggers on **cold start** or **after the app has been idle/backgrounded a long
 * time** (the system reclaims the app's GPU resources), and we have not been able to reproduce it on
 * demand. The full investigation — and why the *web* blank-text bug was a separate, server-side cause
 * (Compose's `.cvr` string table served as `index.html`) that does NOT implicate iOS — is recorded in
 * `BLANK_TEXT_INVESTIGATION.md`.
 *
 * Since we can't catch it live, we instrument instead (per the project's debugging rule: if you can't
 * capture evidence, install the thing that will). We log the lifecycle + font-cache CONTEXT around the
 * moments it strikes, so that when a user reports a recurrence we can read `homebase.log` and tell
 * which trigger it was (cold-start race vs. idle GPU eviction vs. memory pressure).
 *
 * Honest about limits: the GPU glyph-atlas residency itself lives on the skiko `DirectContext` that
 * Compose owns internally — there is no public handle to it, so we cannot read it directly. What we
 * CAN read is the global skiko `Graphics` font-cache counters plus iOS lifecycle / memory-pressure
 * signals, which together strongly discriminate the trigger. That gathering is platform-specific and
 * supplied by a [Probe]; platforms without one (Android/Desktop/Web) make every call here a cheap
 * no-op, since this bug is iOS-only.
 */
object GpuTextDiagnostics {
    const val TAG = "GpuTextDiag"

    /** Why a snapshot was taken — the discriminating dimension when reading the log back. */
    enum class Event { ColdStart, Foreground, MemoryWarning, ManualCapture }

    /** A point-in-time reading of everything we can actually observe at one of the [Event] moments. */
    data class Snapshot(
        val event: Event,
        val note: String? = null,
        /** Wall-clock time the app spent backgrounded before this foreground (the "idle" signal). */
        val backgroundDurationMs: Long? = null,
        /** skiko global font (strike) cache — bytes currently held. */
        val fontCacheUsedBytes: Long? = null,
        /** skiko global font (strike) cache — byte limit. */
        val fontCacheLimitBytes: Long? = null,
        /** skiko global font (strike) cache — number of strikes held. */
        val fontCacheCountUsed: Int? = null,
        /** `NSProcessInfo.thermalState` name (nominal/fair/serious/critical). */
        val thermalState: String? = null,
        /** `NSProcessInfo.isLowPowerModeEnabled`. */
        val lowPowerMode: Boolean? = null,
        /** Device physical memory, MB (`NSProcessInfo.physicalMemory`). */
        val physicalMemoryMb: Long? = null,
    )

    /** Platform hook that gathers a [Snapshot]. Registered by the iOS layer; absent elsewhere. */
    fun interface Probe {
        fun snapshot(event: Event, note: String?, backgroundDurationMs: Long?): Snapshot
    }

    @Volatile
    private var probe: Probe? = null

    /** Called once by the iOS layer during app init. Idempotent (last registration wins). */
    fun register(probe: Probe) {
        this.probe = probe
    }

    /** Take and log a snapshot for [event]. No-op when no platform [Probe] is registered. */
    fun log(event: Event, note: String? = null, backgroundDurationMs: Long? = null) {
        val p = probe ?: return
        val snapshot = p.snapshot(event, note, backgroundDurationMs)
        Logger.i(tag = TAG) { format(snapshot) }
    }

    /**
     * Manual marker — call this the instant blank text is observed (e.g. from an iOS shake handler),
     * so the log carries a snapshot taken AT the failure, not just at the surrounding lifecycle events.
     */
    fun capture(reason: String) = log(Event.ManualCapture, note = reason)

    /** Pure, deterministic one-line formatter — unit-tested without any platform. */
    fun format(s: Snapshot): String = buildString {
        append("event=").append(s.event.name)
        s.note?.let { append(" note=\"").append(it).append('"') }
        s.backgroundDurationMs?.let { append(" bgMs=").append(it) }
        s.fontCacheUsedBytes?.let { append(" fontCacheUsed=").append(it) }
        s.fontCacheLimitBytes?.let { append(" fontCacheLimit=").append(it) }
        s.fontCacheCountUsed?.let { append(" fontCacheCount=").append(it) }
        s.thermalState?.let { append(" thermal=").append(it) }
        s.lowPowerMode?.let { append(" lowPower=").append(it) }
        s.physicalMemoryMb?.let { append(" physMemMb=").append(it) }
    }
}
