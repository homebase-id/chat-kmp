package id.homebase.api.file.zip

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JDK's own reader, which works from the central directory as desktop unzippers do. */
class ZipStreamWriterJvmTest {

    private suspend fun write(file: File, zip64From: Long = 0xFFFFFFFFL, block: suspend ZipStreamWriter.() -> Unit) {
        file.outputStream().buffered().use { out ->
            ZipStreamWriter({ out.write(it) }, TimeZone.currentSystemDefault(), zip64From).apply { block() }.finish()
        }
    }

    @Test
    fun theJdkReadsWhatTheWriterWrote() = runTest {
        val file = File.createTempFile("zip-writer", ".zip")
        try {
            val modified = 1_709_634_031_000L
            val body = ByteArray(200_000) { (it * 7).toByte() }
            write(file) {
                addFolder(listOf("Docs"), modified)
                addFile(listOf("Docs", "report.pdf"), modified, executable = false, expectedSize = body.size.toLong(), flowOf(body))
                addFile(listOf("Résumé.txt"), modified, executable = false, expectedSize = 2, flowOf("ok".encodeToByteArray()))
            }
            ZipFile(file).use { zip ->
                assertEquals(listOf("Docs/", "Docs/report.pdf", "Résumé.txt"), zip.entries().toList().map { it.name })
                assertTrue(zip.getEntry("Docs/").isDirectory)
                assertContentEquals(body, zip.getInputStream(zip.getEntry("Docs/report.pdf")).readBytes())
                assertEquals(modified, zip.getEntry("Résumé.txt").lastModifiedTime.toMillis())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun theJdkReadsZip64Records() = runTest {
        val file = File.createTempFile("zip-writer-64", ".zip")
        try {
            val big = ByteArray(5_000) { it.toByte() }
            write(file, zip64From = 1_000) {
                addFile(listOf("a"), 0, executable = false, expectedSize = 600, flowOf(ByteArray(600) { 1 }))
                addFile(listOf("big"), 0, executable = false, expectedSize = big.size.toLong(), flowOf(big))
                addFile(listOf("c"), 0, executable = false, expectedSize = 3, flowOf(byteArrayOf(1, 2, 3)))
            }
            ZipFile(file).use { zip ->
                assertContentEquals(big, zip.getInputStream(zip.getEntry("big")).readBytes())
                assertContentEquals(byteArrayOf(1, 2, 3), zip.getInputStream(zip.getEntry("c")).readBytes())
                assertEquals(3, zip.size())
            }
        } finally {
            file.delete()
        }
    }
}
