package id.homebase.core.diagnostics

import kotlin.concurrent.Volatile

/**
 * Memory context attached to a [MainThreadWatchdog] stall breadcrumb, so a memory-pressure
 * freeze leaves numbers to act on instead of just a duration.
 *
 * Follows the same registration-object shape as [GpuTextDiagnostics]: platform code registers a
 * [Probe] during app init; [capture] is a no-op (returns `null`) wherever none is registered.
 */
object MemoryDiagnostics {
    /** A point-in-time reading of whatever memory figures the platform can cheaply provide. */
    data class Snapshot(
        val freeMemoryMb: Long? = null,
        val totalMemoryMb: Long? = null,
        val maxMemoryMb: Long? = null,
        /** Android `Debug.MemoryInfo` total PSS, MB. */
        val nativePssMb: Long? = null,
        /** iOS `NSProcessInfo.physicalMemory`, MB. */
        val physicalMemoryMb: Long? = null,
    )

    fun interface Probe {
        fun snapshot(): Snapshot
    }

    @Volatile
    private var probe: Probe? = null

    /** Called once by platform code during app init. Idempotent (last registration wins). */
    fun register(probe: Probe) {
        this.probe = probe
    }

    /** Takes a snapshot, or `null` when no platform [Probe] is registered. */
    fun capture(): Snapshot? = probe?.snapshot()

    /** Pure, deterministic one-line formatter — unit-tested without any platform. */
    fun format(s: Snapshot): String = buildString {
        s.freeMemoryMb?.let { append("freeMb=").append(it) }
        s.totalMemoryMb?.let { append(" totalMb=").append(it) }
        s.maxMemoryMb?.let { append(" maxMb=").append(it) }
        s.nativePssMb?.let { append(" nativePssMb=").append(it) }
        s.physicalMemoryMb?.let { append(" physMemMb=").append(it) }
    }
}

/**
 * Registers this platform's [MemoryDiagnostics.Probe], if it's self-contained (needs no
 * `Context`/app handle). Called once from [MainThreadWatchdog.start]. Android/JVM register here;
 * iOS registers explicitly from `homebase-core`'s app-init path instead (a different module,
 * alongside its existing `GpuTextDiagnostics` registration) — this is a no-op there. A no-op on
 * wasmJs too.
 */
internal expect fun installMemoryDiagnostics()
