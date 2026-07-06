package id.homebase.core.diagnostics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo

/**
 * iOS implementation of [MemoryDiagnostics] (see that class): reports device physical memory via
 * `NSProcessInfo`, the same reading already used by [GpuTextDiagnostics]'s iOS probe.
 */
@OptIn(ExperimentalForeignApi::class)
private object IosMemoryProbe : MemoryDiagnostics.Probe {
    override fun snapshot(): MemoryDiagnostics.Snapshot = MemoryDiagnostics.Snapshot(
        physicalMemoryMb = (NSProcessInfo.processInfo.physicalMemory / (1024uL * 1024uL)).toLong(),
    )
}

private var installed = false

/**
 * Wire up [MemoryDiagnostics] on iOS. Call once, early — from `MainViewController.initializeApp()`,
 * alongside [installGpuTextDiagnostics]. Idempotent.
 */
fun installMemoryDiagnostics() {
    if (installed) return
    installed = true
    MemoryDiagnostics.register(IosMemoryProbe)
}
