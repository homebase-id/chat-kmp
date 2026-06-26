package id.homebase.core.diagnostics

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile

/**
 * Slim validation logging for the (now-fixed) iOS "all text blank, frames fine" bug.
 *
 * Root cause (see `BLANK_TEXT_INVESTIGATION.md`): iOS could launch the app process into the
 * background, where Compose rendered its first frame while `applicationState == .background`; iOS
 * rejects background Metal submissions, so the GPU glyph atlas was born dead and all text was blank
 * at the first real foreground. The fix is the prevention in `ContentView` — `ComposeView` is not
 * built until the scene is first `.active`, so Compose can never render while backgrounded.
 *
 * This logs a font-cache snapshot at the lifecycle moments that confirm the fix keeps holding in the
 * fleet: a background-launched session (a `ColdStart` with no immediate `Foreground`) must now show
 * `fontCacheUsed=0 count=0` at its first `Foreground`, not the poisoned `6889/4` fingerprint that
 * meant Compose had already drawn while backgrounded. The platform reading is supplied by a [Probe]
 * (iOS only); platforms without one make every call here a cheap no-op.
 */
object GpuTextDiagnostics {
    const val TAG = "GpuTextDiag"

    /** Why a snapshot was taken — the discriminating dimension when reading the log back. */
    enum class Event { ColdStart, Foreground }

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
