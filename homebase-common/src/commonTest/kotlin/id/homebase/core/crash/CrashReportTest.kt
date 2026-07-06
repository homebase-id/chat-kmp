package id.homebase.core.crash

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class CrashReportTest {
    private val meta = CrashMetadata(
        appVersion = "1.2.3",
        buildType = "release",
        platform = "Android 14 (API 34)",
        device = "Google Pixel 7",
        buildTime = "2026-06-10 09:00:00",
    )

    @Test
    fun render_includes_all_sections() {
        val out = CrashReport.render(
            metadata = meta,
            timeIso = "2026-06-16T08:31:04Z",
            thread = "main",
            exceptionLine = "kotlin.IllegalStateException: boom",
            stackText = "at Foo.bar(Foo.kt:1)",
            logTail = "10:00 some log line",
        )
        assertContains(out, "Homebase crash report")
        assertContains(out, "1.2.3 (release, built 2026-06-10 09:00:00)")
        assertContains(out, "Android 14 (API 34)")
        assertContains(out, "Google Pixel 7")
        assertContains(out, "Thread:    main")
        assertContains(out, "kotlin.IllegalStateException: boom")
        assertContains(out, "----- Stack trace -----")
        assertContains(out, "at Foo.bar(Foo.kt:1)")
        assertContains(out, "----- Recent log (tail) -----")
        assertContains(out, "10:00 some log line")
    }

    @Test
    fun render_omits_log_section_when_null() {
        val out = CrashReport.render(meta, "t", "main", "E: x", "stack", logTail = null)
        assertTrue("Recent log" !in out)
    }

    @Test
    fun render_tolerates_null_metadata() {
        val out = CrashReport.render(null, "t", "bg", "E: x", "stack", null)
        assertContains(out, "Thread:    bg")
        assertTrue("App:" !in out)
    }
}
