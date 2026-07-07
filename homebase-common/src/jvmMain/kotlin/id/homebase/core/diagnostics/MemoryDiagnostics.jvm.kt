package id.homebase.core.diagnostics

/** Desktop (JVM) memory snapshot for [MemoryDiagnostics]: JVM heap figures only. */
private class JvmMemoryProbe : MemoryDiagnostics.Probe {
    override fun snapshot(): MemoryDiagnostics.Snapshot {
        val runtime = Runtime.getRuntime()
        return MemoryDiagnostics.Snapshot(
            freeMemoryMb = runtime.freeMemory() / BYTES_PER_MB,
            totalMemoryMb = runtime.totalMemory() / BYTES_PER_MB,
            maxMemoryMb = runtime.maxMemory() / BYTES_PER_MB,
        )
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}

internal actual fun installMemoryDiagnostics() {
    MemoryDiagnostics.register(JvmMemoryProbe())
}
