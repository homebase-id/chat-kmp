package id.homebase.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the pure [GpuTextDiagnostics.format] formatter — the part of the iOS blank-text
 * instrumentation that has no platform dependency. The platform gathering (skiko font cache,
 * NSProcessInfo, lifecycle observers) lives in the iOS `nativeMain` probe and is exercised on device.
 */
class GpuTextDiagnosticsTest {

    @Test
    fun minimalSnapshotShowsOnlyTheEvent() {
        val line = GpuTextDiagnostics.format(
            GpuTextDiagnostics.Snapshot(event = GpuTextDiagnostics.Event.ColdStart)
        )
        assertEquals("event=ColdStart", line)
    }

    @Test
    fun nullFieldsAreOmittedNotPrintedAsNull() {
        val line = GpuTextDiagnostics.format(
            GpuTextDiagnostics.Snapshot(
                event = GpuTextDiagnostics.Event.Foreground,
                backgroundDurationMs = 95_000,
            )
        )
        assertEquals("event=Foreground bgMs=95000", line)
    }

    @Test
    fun fullSnapshotEmitsEveryFieldInOrder() {
        val line = GpuTextDiagnostics.format(
            GpuTextDiagnostics.Snapshot(
                event = GpuTextDiagnostics.Event.Foreground,
                note = "after lunch",
                backgroundDurationMs = 5_400_000,
                fontCacheUsedBytes = 16_504,
                fontCacheLimitBytes = 33_554_432,
                fontCacheCountUsed = 42,
                thermalState = "serious",
                lowPowerMode = true,
                physicalMemoryMb = 6_144,
            )
        )
        assertEquals(
            "event=Foreground note=\"after lunch\" bgMs=5400000 " +
                "fontCacheUsed=16504 fontCacheLimit=33554432 fontCacheCount=42 " +
                "thermal=serious lowPower=true physMemMb=6144",
            line,
        )
    }
}
