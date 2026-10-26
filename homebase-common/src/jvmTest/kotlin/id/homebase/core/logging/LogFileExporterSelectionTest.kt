package id.homebase.core.logging

import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogFileExporterSelectionTest {
    private fun dir() = Files.createTempDirectory("log-export").toFile().apply { deleteOnExit() }

    @Test
    fun handsEachHomebaseLogFileToTheSelectorAndWritesItsResultBesideThem() {
        val d = dir()
        d.resolve("homebase.log").writeText("one\ntwo\n")
        d.resolve("homebase-1.log").writeText("three\n")
        d.resolve("unrelated.txt").writeText("ignored\n")
        var seen: List<List<String>> = emptyList()
        val out = LogFileExporter.exportSelection(Path(d.path), "call-report-x.txt") { files ->
            seen = files
            "report"
        }
        assertEquals(setOf(listOf("one", "two", ""), listOf("three", "")), seen.toSet())
        assertEquals("report", d.resolve("call-report-x.txt").readText())
        assertEquals("call-report-x.txt", out?.name)
    }

    @Test
    fun writesNothingWhenTheSelectorHasNothing() {
        val d = dir()
        d.resolve("homebase.log").writeText("one\n")
        assertNull(LogFileExporter.exportSelection(Path(d.path), "call-report-x.txt") { null })
        assertEquals(false, d.resolve("call-report-x.txt").exists())
    }
}
