package id.homebase.api.video.hls

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.readU16
import id.homebase.api.util.ByteBuilder
import id.homebase.api.video.hls.MpegTs.AAC_FRAME
import id.homebase.api.video.hls.MpegTs.NAL_AUD
import id.homebase.api.video.hls.MpegTs.NAL_IDR
import id.homebase.api.video.hls.MpegTs.NAL_PPS
import id.homebase.api.video.hls.MpegTs.NAL_SPS
import id.homebase.api.video.hls.MpegTs.PACKET
import id.homebase.api.video.hls.MpegTs.STREAM_AAC_ADTS
import id.homebase.api.video.hls.MpegTs.STREAM_H264
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Copies the H.264 and AAC of an MPEG-TS into an MP4 without re-encoding, in one pass as the TS arrives: `ftyp`, the
 * samples in one `mdat`, then the `moov` indexing them, and last the `mdat`'s size, written back in place. The index at
 * the end is fine for a file played from disk; only streaming over HTTP wants it first. Throws when the TS has no
 * H.264, or audio that isn't AAC; [label] names the TS in that error.
 */
internal object TsToMp4 {
    suspend fun remux(fileOps: FileOperationsProvider, tsPath: String, outputPath: String) =
        remux(fileOps, fileOps.readFileAsFlow(tsPath, CHUNK), outputPath, tsPath)

    suspend fun remux(fileOps: FileOperationsProvider, ts: Flow<ByteArray>, outputPath: String, label: String) {
        val demuxer = TsDemuxer()
        val table = SampleTable()
        fileOps.writeStream(outputPath, flow {
            emit(SampleTable.FTYP + SampleTable.mdatHeader(0))
            val out = ByteBuilder(CHUNK)
            suspend fun copy(samples: List<TsSample>) = samples.forEach { sample ->
                table.add(sample)
                out.add(sample.data)
                if (out.size >= CHUNK) emit(out.take())
            }
            ts.collect { copy(demuxer.feed(it)) }
            copy(demuxer.finish())
            if (out.size > 0) emit(out.take())
            // A saved video without its sound would be worse than a clear failure.
            check(demuxer.unsupportedAudioType < 0) { "audio stream type 0x${demuxer.unsupportedAudioType.toString(16)} isn't AAC" }
            val avc = checkNotNull(demuxer.avc) { "no H.264 in $label" }
            emit(table.moov(avc, demuxer.aac, firstOffset = (SampleTable.FTYP.size + SampleTable.MDAT_HEADER).toLong()))
        })
        fileOps.overwrite(outputPath, SampleTable.FTYP.size.toLong(), SampleTable.mdatHeader(table.dataBytes))
    }

    private const val CHUNK = 256 * 1024
}

/** A frame as the MP4 stores it: H.264 with 4-byte NAL lengths, or raw AAC. Times in 90 kHz ticks. */
internal class TsSample(val video: Boolean, val dts: Long, val pts: Long, val isKey: Boolean, val size: Int, val data: ByteArray)

/**
 * Splits 188-byte TS packets into PES per stream, and PES into MP4 samples. Tracks are known from the first frames.
 */
internal class TsDemuxer {
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
    private val sample = ByteBuilder(1024 * 1024)
    private val pending = ByteBuilder(512 * 1024)
    private val ready = mutableListOf<TsSample>()
    private val sps = mutableListOf<ByteArray>()
    private val pps = mutableListOf<ByteArray>()
    private var unsupportedAudio = -1
    private var lastVideo90k = -1L
    private var lastAudio90k = -1L
    private var synced = false

    /** The stream type of audio that isn't ADTS AAC, which this can't copy; -1 when there's none. */
    val unsupportedAudioType get() = unsupportedAudio

    fun feed(chunk: ByteArray): List<TsSample> {
        pending.add(chunk)
        packets(final = false)
        return take()
    }

    fun finish(): List<TsSample> {
        packets(final = true)
        if (videoPesOpen) videoPes.drain(::video)
        if (audioPesOpen) audioPes.drain(::audio)
        videoPesOpen = false
        audioPesOpen = false
        return take()
    }

    private fun take(): List<TsSample> = ready.toList().also { ready.clear() }

    // A download decrypts the whole payload as one CBC stream while each HLS segment was encrypted on its own, so
    // every segment boundary leaves pad bytes and a garbled first block (the segment's PAT). As ffmpeg does: in sync,
    // take each packet that starts with the sync byte; out of it, skip to one that is followed by another.
    private fun packets(final: Boolean) {
        val used = pending.peek { b, size ->
            var at = 0
            while (at + PACKET <= size) {
                val next = at + PACKET
                if (b[at].toInt() != SYNC) {
                    synced = false
                    at++
                    continue
                }
                if (!synced) {
                    if (next >= size && !final) break
                    if (next < size && b[next].toInt() != SYNC) {
                        at++
                        continue
                    }
                    synced = true
                }
                packet(b, at)
                at = next
            }
            at
        }
        pending.removeFirst(used)
    }

    private fun packet(b: ByteArray, at: Int) {
        val start = b[at + 1].toInt() and 0x40 != 0
        val pid = readU16(b, at + 1, little = false) and 0x1FFF
        val control = (b[at + 3].toInt() shr 4) and 3
        var p = at + 4
        if (control == 2 || control == 3) p += 1 + (b[p].toInt() and 0xFF)
        val end = at + PACKET
        if (control == 0 || control == 2 || p >= end) return
        when (pid) {
            0 -> if (start) pat(b, p + 1 + (b[p].toInt() and 0xFF), end)
            pmtPid -> if (start) pmt(b, p + 1 + (b[p].toInt() and 0xFF), end)
            videoPid -> {
                if (start) {
                    if (videoPesOpen) videoPes.drain(::video)
                    videoPesOpen = true
                }
                if (videoPesOpen) videoPes.add(b, p, end - p)
            }
            audioPid -> {
                if (start) {
                    if (audioPesOpen) audioPes.drain(::audio)
                    audioPesOpen = true
                }
                if (audioPesOpen) audioPes.add(b, p, end - p)
            }
        }
    }

    private fun sectionEnd(b: ByteArray, s: Int, end: Int) = minOf(end, s + 3 + (readU16(b, s + 1, little = false) and 0x0FFF)) - 4

    private fun pat(b: ByteArray, s: Int, end: Int) {
        val last = sectionEnd(b, s, end)
        var p = s + 8
        while (p + 4 <= last) {
            if (readU16(b, p, little = false) != 0) {
                pmtPid = readU16(b, p + 2, little = false) and 0x1FFF
                return
            }
            p += 4
        }
    }

    private fun pmt(b: ByteArray, s: Int, end: Int) {
        val last = sectionEnd(b, s, end)
        var p = s + 12 + (readU16(b, s + 10, little = false) and 0x0FFF)
        while (p + 5 <= last) {
            val type = b[p].toInt() and 0xFF
            val pid = readU16(b, p + 1, little = false) and 0x1FFF
            if (type == STREAM_H264 && videoPid < 0) videoPid = pid
            if (type == STREAM_AAC_ADTS && audioPid < 0) audioPid = pid
            if (type in OTHER_AUDIO) unsupportedAudio = type
            p += 5 + (readU16(b, p + 3, little = false) and 0x0FFF)
        }
    }

    /** The PES header's timestamps and where its payload starts; null when it isn't a PES with a PTS. */
    private fun pesHeader(pes: ByteArray, size: Int, last: Long): Triple<Long, Long, Int>? {
        if (size < 14 || pes[0].toInt() != 0 || pes[1].toInt() != 0 || pes[2].toInt() != 1) return null
        val flags = (pes[7].toInt() shr 6) and 3
        if (flags and 2 == 0) return null
        val pts = unwrap(timestamp(pes, 9), last)
        val dts = if (flags == 3 && size >= 19) unwrap(timestamp(pes, 14), last) else pts
        return Triple(pts, dts, 9 + (pes[8].toInt() and 0xFF))
    }

    // Timestamps are 33 bits and wrap after 26.5 hours of clock: keep counting past the wrap.
    private fun unwrap(t: Long, last: Long): Long {
        if (last < 0) return t
        var x = t + (last ushr 33 shl 33)
        if (x < last - WRAP / 2) x += WRAP
        if (x > last + WRAP / 2) x -= WRAP
        return x
    }

    private fun timestamp(b: ByteArray, at: Int): Long =
        (((b[at].toLong() shr 1) and 7) shl 30) or ((b[at + 1].toLong() and 0xFF) shl 22) or
            (((b[at + 2].toLong() and 0xFF) shr 1) shl 15) or ((b[at + 3].toLong() and 0xFF) shl 7) or ((b[at + 4].toLong() and 0xFF) shr 1)

    private fun video(pes: ByteArray, end: Int) {
        val (pts, dts, from) = pesHeader(pes, end, lastVideo90k) ?: return
        lastVideo90k = dts
        var size = 0
        var isKey = false
        var slices = 0
        forEachNal(pes, from, end) { nalStart, nalEnd ->
            when (val type = pes[nalStart].toInt() and 0x1F) {
                NAL_SPS -> keepDistinct(sps, pes, nalStart, nalEnd)
                NAL_PPS -> keepDistinct(pps, pes, nalStart, nalEnd)
                NAL_AUD -> Unit
                else -> {
                    if (type == NAL_IDR) isKey = true
                    if (type in 1..5) slices++
                    val length = nalEnd - nalStart
                    size += 4 + length
                    sample.addU32(length.toLong())
                    sample.add(pes, nalStart, length)
                }
            }
        }
        if (avc == null && sps.isNotEmpty() && pps.isNotEmpty()) avc = Mp4Track.Avc(4, sps, pps)
        val data = sample.take()
        if (slices > 0 && avc != null) ready += TsSample(true, dts, pts, isKey, size, data)
    }

    // Annex B: NALs after 00 00 01 start codes; the zero before a 4-byte start code belongs to no NAL. A byte over 1
    // can't end a start code at any of the three positions it would sit in, so the scan steps three at a time past it.
    private inline fun forEachNal(b: ByteArray, from: Int, end: Int, block: (start: Int, end: Int) -> Unit) {
        var start = -1
        var i = from
        while (i + 2 < end) {
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
        if (start in 0 until end) block(start, trimZeros(b, start, end))
    }

    // Every parameter set goes into the index: a stream may use several PPS ids, and the samples carry none.
    private fun keepDistinct(sets: MutableList<ByteArray>, b: ByteArray, start: Int, end: Int) {
        if (sets.size < MAX_PARAMETER_SETS && sets.none { it.size == end - start && b.copyOfRange(start, end).contentEquals(it) }) {
            sets += b.copyOfRange(start, end)
        }
    }

    private fun trimZeros(b: ByteArray, start: Int, end: Int): Int {
        var e = end
        while (e > start + 1 && b[e - 1].toInt() == 0) e--
        return e
    }

    private fun audio(pes: ByteArray, end: Int) {
        val (pts, _, from) = pesHeader(pes, end, lastAudio90k) ?: return
        lastAudio90k = pts
        var at = from
        var frame = 0
        while (at + 7 <= end) {
            if ((pes[at].toInt() and 0xFF) != 0xFF || (pes[at + 1].toInt() and 0xF0) != 0xF0) return
            val headerSize = if (pes[at + 1].toInt() and 1 != 0) 7 else 9
            val length = ((pes[at + 3].toInt() and 3) shl 11) or ((pes[at + 4].toInt() and 0xFF) shl 3) or ((pes[at + 5].toInt() and 0xFF) shr 5)
            if (length < headerSize || at + length > end) return
            val track = aac ?: Mp4Track.Aac(
                profile = (pes[at + 2].toInt() shr 6) and 3,
                sampleRateIndex = (pes[at + 2].toInt() shr 2) and 0xF,
                channels = ((pes[at + 2].toInt() and 1) shl 2) or ((pes[at + 3].toInt() shr 6) and 3),
            ).takeIf { it.sampleRateIndex < Mp4Track.Aac.SAMPLE_RATES.size }?.also { aac = it } ?: return
            val t = pts + frame * AAC_FRAME * 90_000L / track.sampleRate
            val data = pes.copyOfRange(at + headerSize, at + length)
            ready += TsSample(false, t, t, true, length - headerSize, data)
            frame++
            at += length
        }
    }

    private companion object {
        const val SYNC = 0x47
        const val WRAP = 1L shl 33
        const val MAX_PARAMETER_SETS = 32
        // MPEG-1/2 audio, LATM AAC, AC-3 and E-AC-3.
        val OTHER_AUDIO = setOf(0x03, 0x04, 0x11, 0x81, 0x87)
    }
}
