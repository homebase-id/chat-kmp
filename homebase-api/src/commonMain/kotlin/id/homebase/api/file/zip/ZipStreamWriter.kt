package id.homebase.api.file.zip

import id.homebase.api.crypto.Crc32c
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.Buffer
import kotlin.time.Instant

/**
 * Writes a ZIP archive front to back to [out], never seeking and holding nothing but one small record per entry, so an
 * archive of any size streams through. Entries are stored, not compressed: what people zip here is mostly photos and
 * video, and deflate isn't available on every platform. A file's CRC and sizes follow its bytes in a data descriptor,
 * and ZIP64 records are written where a size, an offset or the entry count outgrows the classic fields.
 *
 * An entry is named by its path segments, which may come from any OS; none can make it land outside the folder the
 * archive is unpacked into. Names are UTF-8, and modified times are written both as local DOS time and as UTC.
 *
 * A failure part way through an entry leaves it recorded, so whatever [out] received must be thrown away whole.
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
        val mode: Int,
        val dosTime: Int,
        val dosDate: Int,
        val unixSeconds: Int,
        val offset: Long,
        val zip64Local: Boolean,
        var crc: UInt = 0u,
        var size: Long = 0,
    ) {
        val flags get() = if (folder) FLAG_UTF8 else FLAG_UTF8 or FLAG_DESCRIPTOR
    }

    private val entries = mutableListOf<Entry>()
    private var written = 0L
    private var finished = false

    suspend fun addFolder(path: List<String>, modifiedMs: Long) {
        start(zipPath(path) + "/", folder = true, MODE_FOLDER, modifiedMs, zip64Local = false)
    }

    /**
     * Streams [data] in as the file at [path]. [expectedSize] decides whether its local header is ZIP64; a file that
     * reaches 4 GiB without having said so up front can't be described any more, so it fails the archive.
     */
    suspend fun addFile(path: List<String>, modifiedMs: Long, executable: Boolean, expectedSize: Long, data: Flow<ByteArray>) {
        val name = zipPath(path)
        val entry = start(name, folder = false, if (executable) MODE_EXECUTABLE else MODE_FILE, modifiedMs, expectedSize >= zip64From)
        var crc = 0u
        var size = 0L
        data.collect { chunk ->
            crc = Crc32c.calculateCrc32c(crc, chunk)
            size += chunk.size
            if (!entry.zip64Local && size >= zip64From) throw ZipTooLargeException(name)
            write(chunk)
        }
        entry.crc = crc
        entry.size = size
        write(
            Buffer().apply {
                writeIntLe(DESCRIPTOR_SIG)
                writeIntLe(crc.toInt())
                if (entry.zip64Local) {
                    writeLongLe(size)
                    writeLongLe(size)
                } else {
                    writeIntLe(size.toInt())
                    writeIntLe(size.toInt())
                }
            }.readByteArray()
        )
    }

    /** Writes the central directory; the archive is complete once this returns. */
    suspend fun finish() {
        check(!finished) { "finished already" }
        finished = true
        val directoryStart = written
        val directory = Buffer()
        for (entry in entries) {
            directory.centralHeader(entry)
            if (directory.size >= FLUSH_AT) write(directory.readByteArray())
        }
        write(directory.readByteArray())
        val directorySize = written - directoryStart
        val count = entries.size.toLong()
        val zip64 = count >= MAX_16 || directorySize >= zip64From || directoryStart >= zip64From
        write(
            Buffer().apply {
                if (zip64) {
                    writeIntLe(ZIP64_END_SIG)
                    writeLongLe(44)
                    writeShortLe(VERSION_MADE_BY)
                    writeShortLe(VERSION_ZIP64)
                    writeIntLe(0)
                    writeIntLe(0)
                    writeLongLe(count)
                    writeLongLe(count)
                    writeLongLe(directorySize)
                    writeLongLe(directoryStart)
                    writeIntLe(ZIP64_LOCATOR_SIG)
                    writeIntLe(0)
                    writeLongLe(written)
                    writeIntLe(1)
                }
                writeIntLe(END_SIG)
                writeShortLe(0)
                writeShortLe(0)
                writeShortLe(if (zip64) MAX_16.toInt() else count.toInt())
                writeShortLe(if (zip64) MAX_16.toInt() else count.toInt())
                writeIntLe(if (zip64) MAX_32.toInt() else directorySize.toInt())
                writeIntLe(if (zip64) MAX_32.toInt() else directoryStart.toInt())
                writeShortLe(0)
            }.readByteArray()
        )
    }

    /** Records an entry and writes its local header. */
    private suspend fun start(name: String, folder: Boolean, mode: Int, modifiedMs: Long, zip64Local: Boolean): Entry {
        check(!finished) { "finished already" }
        val time = Instant.fromEpochMilliseconds(modifiedMs).toLocalDateTime(timeZone)
        val early = time.year < 1980
        val year = minOf(time.year, 2107)
        val entry = Entry(
            name = name.encodeToByteArray(),
            folder = folder,
            mode = mode,
            dosTime = if (early) 0 else (time.hour shl 11) or (time.minute shl 5) or (time.second / 2),
            dosDate = if (early) (1 shl 5) or 1 else ((year - 1980) shl 9) or (time.month.ordinal + 1 shl 5) or time.day,
            unixSeconds = (modifiedMs / 1000).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            offset = written,
            zip64Local = zip64Local,
        )
        entries += entry
        write(
            Buffer().apply {
                writeIntLe(LOCAL_SIG)
                writeShortLe(if (zip64Local) VERSION_ZIP64 else VERSION_PLAIN)
                writeShortLe(entry.flags)
                writeShortLe(0)
                writeShortLe(entry.dosTime)
                writeShortLe(entry.dosDate)
                writeIntLe(0)
                writeIntLe(if (zip64Local) MAX_32.toInt() else 0)
                writeIntLe(if (zip64Local) MAX_32.toInt() else 0)
                writeShortLe(entry.name.size)
                writeShortLe((if (zip64Local) 20 else 0) + TIME_EXTRA_SIZE)
                write(entry.name)
                if (zip64Local) {
                    writeShortLe(ZIP64_EXTRA)
                    writeShortLe(16)
                    writeLongLe(0)
                    writeLongLe(0)
                }
                timeExtra(entry)
            }.readByteArray()
        )
        return entry
    }

    private fun Buffer.centralHeader(entry: Entry) {
        val bigSize = entry.zip64Local || entry.size >= zip64From
        val bigOffset = entry.offset >= zip64From
        val zip64Size = (if (bigSize) 16 else 0) + (if (bigOffset) 8 else 0)
        writeIntLe(CENTRAL_SIG)
        writeShortLe(VERSION_MADE_BY)
        writeShortLe(if (zip64Size > 0) VERSION_ZIP64 else VERSION_PLAIN)
        writeShortLe(entry.flags)
        writeShortLe(0)
        writeShortLe(entry.dosTime)
        writeShortLe(entry.dosDate)
        writeIntLe(entry.crc.toInt())
        writeIntLe(if (bigSize) MAX_32.toInt() else entry.size.toInt())
        writeIntLe(if (bigSize) MAX_32.toInt() else entry.size.toInt())
        writeShortLe(entry.name.size)
        writeShortLe((if (zip64Size > 0) 4 + zip64Size else 0) + TIME_EXTRA_SIZE)
        writeShortLe(0)
        writeShortLe(0)
        writeShortLe(0)
        writeIntLe((entry.mode shl 16) or (if (entry.folder) DOS_FOLDER else 0))
        writeIntLe(if (bigOffset) MAX_32.toInt() else entry.offset.toInt())
        write(entry.name)
        if (zip64Size > 0) {
            writeShortLe(ZIP64_EXTRA)
            writeShortLe(zip64Size)
            if (bigSize) {
                writeLongLe(entry.size)
                writeLongLe(entry.size)
            }
            if (bigOffset) writeLongLe(entry.offset)
        }
        timeExtra(entry)
    }

    private fun Buffer.timeExtra(entry: Entry) {
        writeShortLe(TIME_EXTRA)
        writeShortLe(5)
        writeByte(1)
        writeIntLe(entry.unixSeconds)
    }

    private suspend fun write(bytes: ByteArray) {
        out(bytes)
        written += bytes.size
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
        const val FLUSH_AT = 64 * 1024L
    }
}

class ZipTooLargeException(path: String) : Exception("$path outgrew the size it was added with")

internal fun zipPath(segments: List<String>): String = segments.joinToString("/") { segment ->
    val clean = buildString { segment.forEach { append(if (it == '/' || it == '\\' || it < ' ' || it == '\u007F') '_' else it) } }
    if (clean.isEmpty() || clean == "." || clean == "..") "_" else clean
}
