package id.homebase.api.file.zip

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Writes a ZIP archive front to back to [out], never seeking and holding nothing but one small record per entry, so an
 * archive of any size streams through. Entries are stored, not compressed: what people zip here is mostly photos and
 * video, and deflate isn't available on every platform. A file's CRC and sizes follow its bytes in a data descriptor,
 * and ZIP64 records are written where a size, an offset or the entry count outgrows the classic fields.
 *
 * Names are UTF-8 and pass through [zipPath]. Modified times are written both as local DOS time and as UTC.
 */
class ZipStreamWriter(
    private val out: suspend (ByteArray) -> Unit,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    // Lowered by tests to reach the ZIP64 paths without writing 4 GiB.
    private val zip64From: Long = MAX_32,
) {
    private class Entry(
        val name: ByteArray,
        val folder: Boolean,
        val executable: Boolean,
        val dosTime: Int,
        val dosDate: Int,
        val unixSeconds: Int,
        val offset: Long,
        val zip64Local: Boolean,
        var crc: Long = 0,
        var size: Long = 0,
    )

    private val entries = mutableListOf<Entry>()
    private var written = 0L
    private var finished = false

    suspend fun addFolder(path: String, modifiedMs: Long) {
        add(entry(path.trimEnd('/') + "/", folder = true, executable = false, modifiedMs, zip64Local = false))
    }

    /**
     * Streams [data] in as the file at [path]. [expectedSize] decides whether its local header is ZIP64; a file that
     * reaches 4 GiB without having said so up front can't be described any more, so it fails the archive.
     */
    suspend fun addFile(path: String, modifiedMs: Long, executable: Boolean, expectedSize: Long, data: Flow<ByteArray>) {
        val entry = entry(path, folder = false, executable, modifiedMs, zip64Local = expectedSize >= zip64From)
        add(entry)
        val crc = Crc32()
        var size = 0L
        data.collect { chunk ->
            crc.update(chunk)
            size += chunk.size
            if (!entry.zip64Local && size >= zip64From) throw ZipTooLargeException(path)
            write(chunk)
        }
        entry.crc = crc.value
        entry.size = size
        write(
            LittleEndian().apply {
                int(DESCRIPTOR_SIG)
                int(entry.crc)
                if (entry.zip64Local) {
                    long(size)
                    long(size)
                } else {
                    int(size)
                    int(size)
                }
            }.bytes()
        )
    }

    /** Writes the central directory; the archive is complete once this returns. */
    suspend fun finish() {
        check(!finished) { "finished already" }
        finished = true
        val directoryStart = written
        for (entry in entries) write(centralHeader(entry))
        val directorySize = written - directoryStart
        val count = entries.size.toLong()
        val zip64 = count >= MAX_16 || directorySize >= zip64From || directoryStart >= zip64From
        if (zip64) {
            val recordAt = written
            write(
                LittleEndian().apply {
                    int(ZIP64_END_SIG)
                    long(44)
                    short(VERSION_MADE_BY)
                    short(VERSION_ZIP64)
                    int(0)
                    int(0)
                    long(count)
                    long(count)
                    long(directorySize)
                    long(directoryStart)
                    int(ZIP64_LOCATOR_SIG)
                    int(0)
                    long(recordAt)
                    int(1)
                }.bytes()
            )
        }
        write(
            LittleEndian().apply {
                int(END_SIG)
                short(0)
                short(0)
                short(if (zip64) MAX_16 else count)
                short(if (zip64) MAX_16 else count)
                int(if (zip64) MAX_32 else directorySize)
                int(if (zip64) MAX_32 else directoryStart)
                short(0)
            }.bytes()
        )
    }

    private fun entry(path: String, folder: Boolean, executable: Boolean, modifiedMs: Long, zip64Local: Boolean): Entry {
        check(!finished) { "finished already" }
        val time = Instant.fromEpochMilliseconds(modifiedMs).toLocalDateTime(timeZone)
        val dos = if (time.year < 1980) {
            0 to ((1 shl 5) or 1)
        } else {
            val year = minOf(time.year, 2107)
            ((time.hour shl 11) or (time.minute shl 5) or (time.second / 2)) to
                (((year - 1980) shl 9) or (time.month.ordinal + 1 shl 5) or time.day)
        }
        return Entry(
            name = path.encodeToByteArray(),
            folder = folder,
            executable = executable,
            dosTime = dos.first,
            dosDate = dos.second,
            unixSeconds = (modifiedMs / 1000).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            offset = written,
            zip64Local = zip64Local,
        )
    }

    private suspend fun add(entry: Entry) {
        entries += entry
        write(
            LittleEndian().apply {
                int(LOCAL_SIG)
                short(if (entry.zip64Local) VERSION_ZIP64 else VERSION_PLAIN)
                short(if (entry.folder) FLAG_UTF8 else FLAG_UTF8 or FLAG_DESCRIPTOR)
                short(0)
                short(entry.dosTime)
                short(entry.dosDate)
                int(0)
                int(if (entry.zip64Local) MAX_32 else 0)
                int(if (entry.zip64Local) MAX_32 else 0)
                short(entry.name.size)
                short((if (entry.zip64Local) 20 else 0) + TIME_EXTRA_SIZE)
                bytes(entry.name)
                if (entry.zip64Local) {
                    short(ZIP64_EXTRA)
                    short(16)
                    long(0)
                    long(0)
                }
                timeExtra(entry)
            }.bytes()
        )
    }

    private fun centralHeader(entry: Entry): ByteArray {
        val bigSize = entry.zip64Local || entry.size >= zip64From
        val bigOffset = entry.offset >= zip64From
        val zip64Size = (if (bigSize) 16 else 0) + (if (bigOffset) 8 else 0)
        val mode = when {
            entry.folder -> MODE_FOLDER
            entry.executable -> MODE_EXECUTABLE
            else -> MODE_FILE
        }
        return LittleEndian().apply {
            int(CENTRAL_SIG)
            short(VERSION_MADE_BY)
            short(if (zip64Size > 0) VERSION_ZIP64 else VERSION_PLAIN)
            short(if (entry.folder) FLAG_UTF8 else FLAG_UTF8 or FLAG_DESCRIPTOR)
            short(0)
            short(entry.dosTime)
            short(entry.dosDate)
            int(entry.crc)
            int(if (bigSize) MAX_32 else entry.size)
            int(if (bigSize) MAX_32 else entry.size)
            short(entry.name.size)
            short((if (zip64Size > 0) 4 + zip64Size else 0) + TIME_EXTRA_SIZE)
            short(0)
            short(0)
            short(0)
            int((mode.toLong() shl 16) or (if (entry.folder) DOS_FOLDER else 0).toLong())
            int(if (bigOffset) MAX_32 else entry.offset)
            bytes(entry.name)
            if (zip64Size > 0) {
                short(ZIP64_EXTRA)
                short(zip64Size.toLong())
                if (bigSize) {
                    long(entry.size)
                    long(entry.size)
                }
                if (bigOffset) long(entry.offset)
            }
            timeExtra(entry)
        }.bytes()
    }

    private fun LittleEndian.timeExtra(entry: Entry) {
        short(TIME_EXTRA)
        short(5)
        byte(1)
        int(entry.unixSeconds.toLong() and MAX_32)
    }

    private suspend fun write(bytes: ByteArray) {
        out(bytes)
        written += bytes.size
    }

    private class LittleEndian {
        private val buffer = ArrayList<Byte>(64)

        fun byte(value: Int) {
            buffer += value.toByte()
        }

        fun short(value: Int) = short(value.toLong())

        fun short(value: Long) {
            repeat(2) { buffer += (value ushr (8 * it)).toByte() }
        }

        fun int(value: Int) = int(value.toLong())

        fun int(value: Long) {
            repeat(4) { buffer += (value ushr (8 * it)).toByte() }
        }

        fun long(value: Long) {
            repeat(8) { buffer += (value ushr (8 * it)).toByte() }
        }

        fun bytes(value: ByteArray) {
            value.forEach { buffer += it }
        }

        fun bytes(): ByteArray = buffer.toByteArray()
    }

    private companion object {
        const val LOCAL_SIG = 0x04034b50
        const val DESCRIPTOR_SIG = 0x08074b50
        const val CENTRAL_SIG = 0x02014b50
        const val ZIP64_END_SIG = 0x06064b50
        const val ZIP64_LOCATOR_SIG = 0x07064b50
        const val END_SIG = 0x06054b50
        const val VERSION_PLAIN = 20
        const val VERSION_ZIP64 = 45
        // Unix, so the external attributes carry the mode and its executable bit.
        const val VERSION_MADE_BY = (3 shl 8) or VERSION_ZIP64
        const val FLAG_DESCRIPTOR = 1 shl 3
        const val FLAG_UTF8 = 1 shl 11
        const val ZIP64_EXTRA = 0x0001
        const val TIME_EXTRA = 0x5455
        const val TIME_EXTRA_SIZE = 9
        const val MODE_FILE = 0x81A4
        const val MODE_EXECUTABLE = 0x81ED
        const val MODE_FOLDER = 0x41ED
        const val DOS_FOLDER = 0x10
        const val MAX_16 = 0xFFFFL
        const val MAX_32 = 0xFFFFFFFFL
    }
}

class ZipTooLargeException(path: String) : Exception("$path outgrew the size it was added with")

/**
 * A ZIP entry name from path segments that came from any OS: a separator, a control character, or a "." or ".."
 * segment can't make an entry land outside the folder it's unpacked into.
 */
fun zipPath(segments: List<String>): String = segments.joinToString("/") { segment ->
    val clean = buildString { segment.forEach { append(if (it == '/' || it == '\\' || it < ' ' || it == '\u007F') '_' else it) } }
    if (clean.isEmpty() || clean == "." || clean == "..") "_" else clean
}
