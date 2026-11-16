package id.homebase.api.video.hls

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.ByteBuilder
import kotlinx.coroutines.flow.flow

/**
 * Copies the H.264 and AAC of an MPEG-TS into an MP4 without re-encoding, as `ffmpeg -c copy -movflags +faststart`
 * does: two reads of the TS, the first to size the samples and build the index, the second to write the file with
 * the index up front. Throws when [tsPath] has no H.264 in MPEG-TS.
 */
internal object TsToMp4 {
    suspend fun remux(fileOps: FileOperationsProvider, tsPath: String, outputPath: String) {
        val table = SampleTable()
        val tracks = demux(fileOps, tsPath, withData = false) { table.add(it) }
        val header = table.header(checkNotNull(tracks.avc) { "no H.264 in $tsPath" }, tracks.aac)
        fileOps.writeStream(outputPath, flow {
            emit(header)
            val out = ByteBuilder(CHUNK)
            var held = 0
            demux(fileOps, tsPath, withData = true) { sample ->
                out.add(sample.data)
                held += sample.data.size
                if (held >= CHUNK) {
                    emit(out.take())
                    held = 0
                }
            }
            if (held > 0) emit(out.take())
        })
    }

    private suspend fun demux(fileOps: FileOperationsProvider, tsPath: String, withData: Boolean, onSample: suspend (TsSample) -> Unit): TsDemuxer {
        val demuxer = TsDemuxer(withData)
        fileOps.readFileAsFlow(tsPath, CHUNK).collect { chunk -> demuxer.feed(chunk).forEach { onSample(it) } }
        demuxer.finish().forEach { onSample(it) }
        return demuxer
    }

    private const val CHUNK = 256 * 1024
}

/** A frame as the MP4 stores it: H.264 with 4-byte NAL lengths, or raw AAC. Times in 90 kHz ticks. */
internal class TsSample(val video: Boolean, val dts: Long, val pts: Long, val isKey: Boolean, val size: Int, val data: ByteArray)

/**
 * Splits 188-byte TS packets into PES per stream, and PES into MP4 samples; without [withData], only their sizes.
 * Tracks are known from the first frames.
 */
internal class TsDemuxer(private val withData: Boolean = true) {
    var avc: Mp4Track.Avc? = null
        private set
    var aac: Mp4Track.Aac? = null
        private set
    private var pmtPid = -1
    private var videoPid = -1
    private var audioPid = -1
    private val videoPes = ByteBuilder(1024 * 1024)
    private val audioPes = ByteBuilder(64 * 1024)
    private var videoPesOpen = false
    private var audioPesOpen = false
    private val carry = ByteArray(PACKET)
    private var carried = 0
    private val ready = mutableListOf<TsSample>()

    fun feed(chunk: ByteArray): List<TsSample> {
        var at = 0
        if (carried > 0) {
            val n = minOf(PACKET - carried, chunk.size)
            chunk.copyInto(carry, carried, 0, n)
            carried += n
            at = n
            if (carried < PACKET) return emptyList()
            packet(carry, 0)
            carried = 0
        }
        while (at + PACKET <= chunk.size) {
            packet(chunk, at)
            at += PACKET
        }
        chunk.copyInto(carry, 0, at, chunk.size)
        carried = chunk.size - at
        return take()
    }

    fun finish(): List<TsSample> {
        if (videoPesOpen) video(videoPes.take())
        if (audioPesOpen) audio(audioPes.take())
        videoPesOpen = false
        audioPesOpen = false
        return take()
    }

    private fun take(): List<TsSample> = ready.toList().also { ready.clear() }

    private fun packet(b: ByteArray, at: Int) {
        check(b[at].toInt() == 0x47) { "lost TS sync" }
        val start = b[at + 1].toInt() and 0x40 != 0
        val pid = ((b[at + 1].toInt() and 0x1F) shl 8) or (b[at + 2].toInt() and 0xFF)
        val control = (b[at + 3].toInt() shr 4) and 3
        var p = at + 4
        if (control == 2 || control == 3) p += 1 + (b[p].toInt() and 0xFF)
        if (control == 0 || control == 2 || p >= at + PACKET) return
        val end = at + PACKET
        when (pid) {
            0 -> if (start) pat(b, p + 1 + (b[p].toInt() and 0xFF), end)
            pmtPid -> if (start) pmt(b, p + 1 + (b[p].toInt() and 0xFF), end)
            videoPid -> {
                if (start) {
                    if (videoPesOpen) video(videoPes.take())
                    videoPesOpen = true
                }
                if (videoPesOpen) videoPes.add(b, p, end - p)
            }
            audioPid -> {
                if (start) {
                    if (audioPesOpen) audio(audioPes.take())
                    audioPesOpen = true
                }
                if (audioPesOpen) audioPes.add(b, p, end - p)
            }
        }
    }

    private fun pat(b: ByteArray, s: Int, end: Int) {
        val sectionEnd = minOf(end, s + 3 + (((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF))) - 4
        var p = s + 8
        while (p + 4 <= sectionEnd) {
            val program = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = ((b[p + 2].toInt() and 0x1F) shl 8) or (b[p + 3].toInt() and 0xFF)
                return
            }
            p += 4
        }
    }

    private fun pmt(b: ByteArray, s: Int, end: Int) {
        val sectionEnd = minOf(end, s + 3 + (((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF))) - 4
        var p = s + 12 + (((b[s + 10].toInt() and 0x0F) shl 8) or (b[s + 11].toInt() and 0xFF))
        while (p + 5 <= sectionEnd) {
            val type = b[p].toInt() and 0xFF
            val pid = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
            if (type == STREAM_H264 && videoPid < 0) videoPid = pid
            if (type == STREAM_AAC_ADTS && audioPid < 0) audioPid = pid
            p += 5 + (((b[p + 3].toInt() and 0x0F) shl 8) or (b[p + 4].toInt() and 0xFF))
        }
    }

    /** The PES header's timestamps and where its payload starts; null when it isn't a PES with a PTS. */
    private fun pesHeader(pes: ByteArray): Triple<Long, Long, Int>? {
        if (pes.size < 14 || pes[0].toInt() != 0 || pes[1].toInt() != 0 || pes[2].toInt() != 1) return null
        val flags = (pes[7].toInt() shr 6) and 3
        if (flags and 2 == 0) return null
        val pts = timestamp(pes, 9)
        val dts = if (flags == 3 && pes.size >= 19) timestamp(pes, 14) else pts
        return Triple(pts, dts, 9 + (pes[8].toInt() and 0xFF))
    }

    private fun timestamp(b: ByteArray, at: Int): Long =
        (((b[at].toLong() shr 1) and 7) shl 30) or ((b[at + 1].toLong() and 0xFF) shl 22) or
            (((b[at + 2].toLong() and 0xFF) shr 1) shl 15) or ((b[at + 3].toLong() and 0xFF) shl 7) or ((b[at + 4].toLong() and 0xFF) shr 1)

    private fun video(pes: ByteArray) {
        val (pts, dts, from) = pesHeader(pes) ?: return
        val out = if (withData) ByteBuilder(pes.size + 64) else null
        var size = 0
        var isKey = false
        var slices = 0
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        forEachNal(pes, from) { nalStart, nalEnd ->
            when (pes[nalStart].toInt() and 0x1F) {
                NAL_SPS -> if (sps == null) sps = pes.copyOfRange(nalStart, nalEnd)
                NAL_PPS -> if (pps == null) pps = pes.copyOfRange(nalStart, nalEnd)
                NAL_AUD -> Unit
                else -> {
                    val type = pes[nalStart].toInt() and 0x1F
                    if (type == NAL_IDR) isKey = true
                    if (type in 1..5) slices++
                    val length = nalEnd - nalStart
                    size += 4 + length
                    if (out != null) {
                        out.addByte(length ushr 24); out.addByte(length ushr 16); out.addByte(length ushr 8); out.addByte(length)
                        out.add(pes, nalStart, length)
                    }
                }
            }
        }
        if (avc == null && sps != null && pps != null) avc = Mp4Track.Avc(4, listOf(sps!!), listOf(pps!!))
        if (slices > 0 && avc != null) ready += TsSample(true, dts, pts, isKey, size, out?.take() ?: EMPTY)
    }

    // Annex B: NALs after 00 00 01 start codes; the zero before a 4-byte start code belongs to no NAL. A byte over 1
    // can't end a start code at any of the three positions it would sit in, so the scan steps three at a time past it.
    private inline fun forEachNal(b: ByteArray, from: Int, block: (start: Int, end: Int) -> Unit) {
        var start = -1
        var i = from
        while (i + 2 < b.size) {
            val c = b[i + 2].toInt() and 0xFF
            if (c > 1) {
                i += 3
            } else if (c == 1 && b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                if (start >= 0) block(start, trimZeros(b, start, i))
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start in 0 until b.size) block(start, trimZeros(b, start, b.size))
    }

    private fun trimZeros(b: ByteArray, start: Int, end: Int): Int {
        var e = end
        while (e > start + 1 && b[e - 1].toInt() == 0) e--
        return e
    }

    private fun audio(pes: ByteArray) {
        val (pts, _, from) = pesHeader(pes) ?: return
        var at = from
        var frame = 0
        while (at + 7 <= pes.size) {
            if ((pes[at].toInt() and 0xFF) != 0xFF || (pes[at + 1].toInt() and 0xF0) != 0xF0) return
            val headerSize = if (pes[at + 1].toInt() and 1 != 0) 7 else 9
            val length = ((pes[at + 3].toInt() and 3) shl 11) or ((pes[at + 4].toInt() and 0xFF) shl 3) or ((pes[at + 5].toInt() and 0xFF) shr 5)
            if (length < headerSize || at + length > pes.size) return
            val track = aac ?: Mp4Track.Aac(
                profile = (pes[at + 2].toInt() shr 6) and 3,
                sampleRateIndex = (pes[at + 2].toInt() shr 2) and 0xF,
                channels = ((pes[at + 2].toInt() and 1) shl 2) or ((pes[at + 3].toInt() shr 6) and 3),
            ).also { aac = it }
            val t = pts + frame * AAC_FRAME * 90_000L / SAMPLE_RATES[track.sampleRateIndex]
            ready += TsSample(false, t, t, true, length - headerSize, if (withData) pes.copyOfRange(at + headerSize, at + length) else EMPTY)
            frame++
            at += length
        }
    }

    companion object {
        const val PACKET = 188
        private val EMPTY = ByteArray(0)
        const val AAC_FRAME = 1024
        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350, 48000, 48000, 48000)
        private const val STREAM_H264 = 0x1B
        private const val STREAM_AAC_ADTS = 0x0F
        private const val NAL_IDR = 5
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_AUD = 9
    }
}
