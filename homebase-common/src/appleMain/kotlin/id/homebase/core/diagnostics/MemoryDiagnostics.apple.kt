package id.homebase.core.diagnostics

/**
 * iOS registers its [MemoryDiagnostics.Probe] explicitly from `homebase-core`'s app-init path
 * (a different module, alongside its existing `GpuTextDiagnostics` registration), not through
 * this installer.
 */
internal actual fun installMemoryDiagnostics() {
    // no-op
}
