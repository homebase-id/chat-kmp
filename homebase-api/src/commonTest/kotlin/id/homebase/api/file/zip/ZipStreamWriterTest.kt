package id.homebase.api.file.zip

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ZipStreamWriterTest {

    private val modified = 1_709_634_031_000L // 2024-03-05T10:20:31Z

    private suspend fun archive(zip64From: Long = 0xFFFFFFFFL, block: suspend ZipStreamWriter.() -> Unit): ByteArray {
        val out = mutableListOf<ByteArray>()
        ZipStreamWriter({ out += it }, TimeZone.UTC, zip64From).apply { block() }.finish()
        return out.fold(ByteArray(0)) { all, chunk -> all + chunk }
    }

    private fun chunks(vararg parts: ByteArray): Flow<ByteArray> = parts.asList().asFlow()

    @Test
    fun crc32MatchesTheStandardCheckValue() {
        assertEquals(0xCBF43926L, Crc32().apply { update("123456789".encodeToByteArray()) }.value)
        assertEquals(0L, Crc32().value)
        val split = Crc32().apply {
            update("1234".encodeToByteArray())
            update("56789".encodeToByteArray())
        }
        assertEquals(0xCBF43926L, split.value)
    }

    @Test
    fun filesAndFoldersReadBackWithTheirNamesBytesModesAndTimes() = runTest {
        val photo = ByteArray(70_000) { (it % 251).toByte() }
        val zip = archive {
            addFolder("Photos", modified)
            addFile("Photos/a.jpg", modified, executable = false, expectedSize = photo.size.toLong(), chunks(photo.copyOfRange(0, 1000), photo.copyOfRange(1000, photo.size)))
            addFile("empty.txt", modified, executable = false, expectedSize = 0, flowOf())
            addFile("Résumé 日本.txt", modified, executable = false, expectedSize = 3, chunks("abc".encodeToByteArray()))
            addFile("run.sh", modified, executable = true, expectedSize = 2, chunks("hi".encodeToByteArray()))
        }

        val entries = ZipReader(zip).entries
        assertEquals(listOf("Photos/", "Photos/a.jpg", "empty.txt", "Résumé 日本.txt", "run.sh"), entries.map { it.name })
        assertContentEquals(photo, entries[1].data)
        assertEquals(0, entries[2].data.size)
        assertContentEquals("abc".encodeToByteArray(), entries[3].data)
        assertEquals(listOf(0x41ED, 0x81A4, 0x81A4, 0x81A4, 0x81ED), entries.map { it.mode })
        assertTrue(entries.all { it.utf8 })
        assertTrue(entries.all { it.unixSeconds == modified / 1000 })
        assertEquals((10 shl 11) or (20 shl 5) or 15, entries[0].dosTime)
        assertEquals((44 shl 9) or (3 shl 5) or 5, entries[0].dosDate)
    }

    @Test
    fun aTimeBefore1980IsWrittenAsTheEarliestDosDate() = runTest {
        val entry = ZipReader(archive { addFolder("old", 0) }).entries.single()
        assertEquals(0, entry.dosTime)
        assertEquals((1 shl 5) or 1, entry.dosDate)
        assertEquals(0L, entry.unixSeconds)
    }

    @Test
    fun largeSizesAndOffsetsGetZip64Records() = runTest {
        val big = ByteArray(250) { it.toByte() }
        val zip = archive(zip64From = 100) {
            addFile("small-1", modified, executable = false, expectedSize = 60, chunks(ByteArray(60) { 1 }))
            addFile("big", modified, executable = false, expectedSize = 250, chunks(big))
            addFile("small-2", modified, executable = false, expectedSize = 5, chunks(ByteArray(5) { 2 }))
        }

        val reader = ZipReader(zip)
        assertTrue(reader.zip64End)
        assertEquals(listOf(false, true, false), reader.entries.map { it.zip64Local })
        assertContentEquals(big, reader.entries[1].data)
        assertContentEquals(ByteArray(5) { 2 }, reader.entries[2].data)
    }

    @Test
    fun aFileThatOutgrowsItsDeclaredSizeFailsTheArchive() = runTest {
        assertFailsWith<ZipTooLargeException> {
            archive(zip64From = 100) { addFile("liar", modified, executable = false, expectedSize = 10, chunks(ByteArray(150))) }
        }
    }

    @Test
    fun aFilesBytesPassStraightThroughWithoutBeingCopiedOrHeld() = runTest {
        val parts = List(5) { ByteArray(64 * 1024) }
        val out = mutableListOf<ByteArray>()
        val writer = ZipStreamWriter({ out += it }, TimeZone.UTC)
        writer.addFile("video.mp4", modified, executable = false, expectedSize = 5 * 64 * 1024L, parts.asFlow())

        // The local header, each part as it was handed in, then the data descriptor.
        assertEquals(parts.size + 2, out.size)
        parts.forEachIndexed { i, part -> assertSame(part, out[i + 1]) }
    }

    @Test
    fun entryNamesCantEscapeTheFolderTheyreUnpackedInto() {
        assertEquals("a/b.txt", zipPath(listOf("a", "b.txt")))
        assertEquals("_/_/x", zipPath(listOf("..", ".", "x")))
        assertEquals("a_b/c_d/_", zipPath(listOf("a/b", "c\\d", "")))
        assertEquals("tab_name", zipPath(listOf("tab\tname")))
    }
}

/** Just enough of a ZIP reader to check what the writer wrote, from the end of the archive like real readers. */
private class ZipReader(private val zip: ByteArray) {
    class Entry(
        val name: String,
        val data: ByteArray,
        val mode: Int,
        val utf8: Boolean,
        val unixSeconds: Long,
        val dosTime: Int,
        val dosDate: Int,
        val zip64Local: Boolean,
    )

    var zip64End = false
        private set

    val entries: List<Entry>

    init {
        val end = (zip.size - 22 downTo 0).first { u32(it) == 0x06054b50L }
        var count = u16(end + 10).toLong()
        var directory = u32(end + 16)
        if (count == 0xFFFFL || directory == 0xFFFFFFFFL) {
            zip64End = true
            check(u32(end - 20) == 0x07064b50L)
            val record = u64(end - 20 + 8).toInt()
            check(u32(record) == 0x06064b50L)
            count = u64(record + 32)
            directory = u64(record + 48)
        }
        var at = directory.toInt()
        entries = List(count.toInt()) {
            check(u32(at) == 0x02014b50L)
            val flags = u16(at + 8)
            val crc = u32(at + 16)
            var size = u32(at + 24)
            val nameLength = u16(at + 28)
            val extraLength = u16(at + 30)
            val commentLength = u16(at + 32)
            val mode = (u32(at + 38) ushr 16).toInt()
            var offset = u32(at + 42)
            val name = zip.copyOfRange(at + 46, at + 46 + nameLength).decodeToString()
            var unixSeconds = -1L
            var extra = at + 46 + nameLength
            val extraEnd = extra + extraLength
            while (extra < extraEnd) {
                val id = u16(extra)
                val length = u16(extra + 2)
                var field = extra + 4
                when (id) {
                    0x0001 -> {
                        if (size == 0xFFFFFFFFL) {
                            size = u64(field)
                            field += 16
                        }
                        if (offset == 0xFFFFFFFFL) offset = u64(field)
                    }
                    0x5455 -> unixSeconds = u32(field + 1).toInt().toLong()
                }
                extra += 4 + length
            }
            at = extraEnd + commentLength

            val local = offset.toInt()
            check(u32(local) == 0x04034b50L)
            val localName = u16(local + 26)
            val localExtra = u16(local + 28)
            // The writer puts a ZIP64 extra first.
            val zip64Local = localExtra > 0 && u16(local + 30 + localName) == 0x0001
            val start = local + 30 + localName + localExtra
            val data = zip.copyOfRange(start, start + size.toInt())
            check(Crc32().apply { update(data) }.value == crc) { "$name: CRC" }
            Entry(name, data, mode, flags and (1 shl 11) != 0, unixSeconds, u16(local + 10), u16(local + 12), zip64Local)
        }
    }

    private fun u8(at: Int) = zip[at].toInt() and 0xFF
    private fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
    private fun u32(at: Int) = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
    private fun u64(at: Int) = u32(at) or (u32(at + 4) shl 32)
}
