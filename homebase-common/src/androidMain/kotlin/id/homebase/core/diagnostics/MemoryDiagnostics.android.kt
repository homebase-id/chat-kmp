package id.homebase.core.diagnostics

import android.os.Debug

/**
 * Android memory snapshot for [MemoryDiagnostics]: JVM heap figures plus native PSS via
 * `Debug.MemoryInfo`. Deliberately Context-free (uses `Debug.getMemoryInfo`, not
 * `ActivityManager.MemoryInfo`, which would need an `Application`/`Context` handle) — this
 * codebase's convention is explicit Context passing per call site
 * (`RichNotificationDisplayer.initialize(this, ...)`), not a global Application-context holder,
 * and this diagnostic isn't worth introducing one for.
 */
private class AndroidMemoryProbe : MemoryDiagnostics.Probe {
    override fun snapshot(): MemoryDiagnostics.Snapshot {
        val runtime = Runtime.getRuntime()
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return MemoryDiagnostics.Snapshot(
            freeMemoryMb = runtime.freeMemory() / BYTES_PER_MB,
            totalMemoryMb = runtime.totalMemory() / BYTES_PER_MB,
            maxMemoryMb = runtime.maxMemory() / BYTES_PER_MB,
            nativePssMb = info.totalPss.toLong() / KB_PER_MB,
        )
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
        const val KB_PER_MB = 1024L
    }
}

internal actual fun installMemoryDiagnostics() {
    MemoryDiagnostics.register(AndroidMemoryProbe())
}
