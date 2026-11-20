package id.homebase.api.video.hls

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.readU16
import id.homebase.api.image.fourCc
import id.homebase.api.image.readU32
import id.homebase.api.image.readU64
import id.homebase.api.video.Mp4Box
import id.homebase.api.video.mp4Boxes
import kotlinx.coroutines.flow.buffer

/** One coded frame: where it lies in the file and when it plays, in 90 kHz ticks. */
internal class Mp4Sample(
    val track: Mp4Track,
    val offset: Long,
    val size: Int,
    val dts90k: Long,
    val pts90k: Long,
    val isKey: Boolean,
)

internal sealed class Mp4Track {
    /** H.264 with [nalLengthSize]-byte NAL lengths; [sps] and [pps] go before every keyframe in Annex B. */
    class Avc(val nalLengthSize: Int, val sps: List<ByteArray>, val pps: List<ByteArray>) : Mp4Track()

    /** AAC: the fields of an ADTS header. */
    class Aac(val profile: Int, val sampleRateIndex: Int, val channels: Int) : Mp4Track() {
        val sampleRate: Int get() = SAMPLE_RATES[sampleRateIndex]

        companion object {
            // ADTS's sampling frequency indexes; above 12 is reserved or explicit, which ADTS can't carry.
            val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        }
    }
}

// A sample table claiming more than this is damaged, not a phone's video.
private const val MAX_SAMPLES = 10_000_000

/**
 * An H.264 + AAC MP4's samples in file order, from its moov alone. Null for anything else.
 * [start90k] is the first video frame's presentation time, [end90k] where the movie ends.
 */
internal class Mp4Movie(val video: Mp4Track.Avc, val audio: Mp4Track.Aac?, val samples: List<Mp4Sample>, val start90k: Long, val end90k: Long) {
    companion object {
        fun parse(moov: ByteArray): Mp4Movie? {
            val samples = mutableListOf<Mp4Sample>()
            var end90k = 0L
            var video: Mp4Track.Avc? = null
            var audio: Mp4Track.Aac? = null
            var start90k = 0L
            val boxes = mp4Boxes(moov, 0, moov.size)
            val mvhd = boxes.firstOrNull { it.type == "mvhd" } ?: return null
            val movieTimescale = if (moov[mvhd.start].toInt() == 1) u32(moov, mvhd.start + 20) else u32(moov, mvhd.start + 12)
            if (movieTimescale <= 0) return null
            for (trak in boxes.filter { it.type == "trak" }) {
                val handler = handlerType(moov, trak)
                // An audio or video track we can't carry would leave the stream silent or blank: ffmpeg cuts that file.
                val track = parseTrack(moov, trak, movieTimescale) ?: if (handler == "soun" || handler == "vide") return null else continue
                when (track.track) {
                    is Mp4Track.Avc -> {
                        // MPEG-TS has no rotation: a rotated video must be turned before it's segmented.
                        if (video != null || isRotated(moov, trak) || track.samples.isEmpty()) return null
                        video = track.track
                        start90k = track.samples.first().pts90k
                    }
                    is Mp4Track.Aac -> if (audio == null) audio = track.track else continue
                }
                samples += track.samples
                end90k = maxOf(end90k, track.end90k)
            }
            samples.sortBy { it.offset }
            return Mp4Movie(video ?: return null, audio, samples, start90k, end90k)
        }

        private class Track(val track: Mp4Track, val samples: List<Mp4Sample>, val end90k: Long)

        // Null for a track that is neither H.264 nor AAC.
        private fun parseTrack(b: ByteArray, trak: Mp4Box, movieTimescale: Int): Track? {
            val mdia = child(b, trak, "mdia") ?: return null
            val mdhd = child(b, mdia, "mdhd") ?: return null
            val timescale = if (b[mdhd.start].toInt() == 1) u32(b, mdhd.start + 20) else u32(b, mdhd.start + 12)
            if (timescale <= 0) return null
            val stbl = child(b, mdia, "minf")?.let { child(b, it, "stbl") } ?: return null
            val stsd = child(b, stbl, "stsd") ?: return null
            val track = sampleEntry(b, stsd) ?: return null

            val sizes = sampleSizes(b, child(b, stbl, "stsz") ?: return null) ?: return null
            val offsets = sampleOffsets(b, stbl, sizes) ?: return null
            val stts = child(b, stbl, "stts") ?: return null
            val decodeTimes = decodeTimes(b, stts, sizes.size) ?: return null
            val ctts = child(b, stbl, "ctts")?.let { compositionOffsets(b, it, sizes.size) }
            val sync = child(b, stbl, "stss")?.let { syncSamples(b, it, sizes.size) }
            val shift = editListShift(b, trak, timescale, movieTimescale)

            fun ticks(t: Long) = t * 90_000 / timescale
            val samples = sizes.indices.map { i ->
                val dts = decodeTimes[i] + shift
                Mp4Sample(
                    track = track,
                    offset = offsets[i],
                    size = sizes[i],
                    dts90k = ticks(dts),
                    pts90k = ticks(dts + (ctts?.get(i) ?: 0)),
                    isKey = track is Mp4Track.Aac || sync == null || sync[i],
                )
            }
            if (samples.isEmpty()) return Track(track, samples, 0)
            return Track(track, samples, samples.maxOf { it.pts90k } + ticks(lastDuration(b, stts)))
        }

        private fun sampleEntry(b: ByteArray, stsd: Mp4Box): Mp4Track? {
            // A full box with an entry count, then the first entry.
            val entry = mp4Boxes(b, stsd.start + 8, stsd.end).firstOrNull() ?: return null
            return when (entry.type) {
                "avc1", "avc3" -> {
                    // VisualSampleEntry: 78 bytes before its child boxes.
                    val avcC = mp4Boxes(b, entry.start + 78, entry.end).firstOrNull { it.type == "avcC" } ?: return null
                    parseAvcC(b, avcC.start, avcC.end)
                }
                "mp4a" -> {
                    // AudioSampleEntry: 28 bytes before its child boxes.
                    val esds = mp4Boxes(b, entry.start + 28, entry.end).firstOrNull { it.type == "esds" } ?: return null
                    parseEsds(b, esds.start + 4, esds.end)
                }
                else -> null
            }
        }

        private fun parseAvcC(b: ByteArray, start: Int, end: Int): Mp4Track.Avc? {
            if (start + 6 > end) return null
            val nalLengthSize = (b[start + 4].toInt() and 3) + 1
            var at = start + 5
            fun sets(count: Int): List<ByteArray>? {
                val list = mutableListOf<ByteArray>()
                repeat(count) {
                    if (at + 2 > end) return null
                    val n = readU16(b, at, little = false)
                    if (at + 2 + n > end) return null
                    list += b.copyOfRange(at + 2, at + 2 + n)
                    at += 2 + n
                }
                return list
            }
            val sps = sets(b[at++].toInt() and 31) ?: return null
            if (at >= end) return null
            val pps = sets(b[at++].toInt() and 0xFF) ?: return null
            return Mp4Track.Avc(nalLengthSize, sps, pps)
        }

        // ES_Descriptor (3) > DecoderConfigDescriptor (4) > DecoderSpecificInfo (5): the AudioSpecificConfig.
        private fun parseEsds(b: ByteArray, start: Int, end: Int): Mp4Track.Aac? {
            var at = start
            fun descriptor(): Pair<Int, Int>? {
                if (at >= end) return null
                val tag = b[at++].toInt() and 0xFF
                var length = 0
                repeat(4) {
                    if (at >= end) return null
                    val byte = b[at++].toInt() and 0xFF
                    length = (length shl 7) or (byte and 0x7F)
                    if (byte and 0x80 == 0) return tag to length
                }
                return tag to length
            }
            if (descriptor()?.first != 3) return null
            val flags = b[at + 2].toInt() and 0xFF
            at += 3
            if (flags and 0x80 != 0) at += 2
            if (flags and 0x40 != 0) at += 1 + (b[at].toInt() and 0xFF)
            if (flags and 0x20 != 0) at += 2
            if (descriptor()?.first != 4) return null
            val objectType = b[at].toInt() and 0xFF
            if (objectType != 0x40) return null
            at += 13
            if (descriptor()?.first != 5 || at + 2 > end) return null
            val config = readU16(b, at, little = false)
            val audioObjectType = config ushr 11
            val sampleRateIndex = (config ushr 7) and 0xF
            val channels = (config ushr 3) and 0xF
            // ADTS carries AAC Main, LC, SSR and LTP only, and no explicit sample rate.
            if (audioObjectType !in 1..4 || sampleRateIndex >= Mp4Track.Aac.SAMPLE_RATES.size || channels !in 1..7) return null
            return Mp4Track.Aac(audioObjectType - 1, sampleRateIndex, channels)
        }

        private fun sampleSizes(b: ByteArray, stsz: Mp4Box): IntArray? {
            val fixed = u32(b, stsz.start + 4)
            val count = u32(b, stsz.start + 8)
            if (count < 0 || count > MAX_SAMPLES || (fixed == 0 && stsz.start + 12 + 4L * count > stsz.end)) return null
            return IntArray(count) { if (fixed != 0) fixed else u32(b, stsz.start + 12 + 4 * it) }
        }

        private fun sampleOffsets(b: ByteArray, stbl: Mp4Box, sizes: IntArray): LongArray? {
            val stco = child(b, stbl, "stco")
            val co64 = child(b, stbl, "co64")
            val chunkOffsets = when {
                stco != null -> LongArray(fits(b, stco, 4) ?: return null) { u32(b, stco.start + 8 + 4 * it).toLong() and 0xFFFFFFFFL }
                co64 != null -> LongArray(fits(b, co64, 8) ?: return null) { readU64(b, co64.start + 8 + 8 * it) }
                else -> return null
            }
            val stsc = child(b, stbl, "stsc") ?: return null
            val runs = fits(b, stsc, 12) ?: return null
            val offsets = LongArray(sizes.size)
            var sample = 0
            for (run in 0 until runs) {
                val at = stsc.start + 8 + 12 * run
                val firstChunk = u32(b, at) - 1
                val perChunk = u32(b, at + 4)
                val lastChunk = if (run + 1 < runs) u32(b, at + 12) - 1 else chunkOffsets.size
                for (chunk in firstChunk until lastChunk) {
                    var offset = chunkOffsets.getOrNull(chunk) ?: return null
                    repeat(perChunk) {
                        if (sample >= sizes.size) return offsets
                        offsets[sample] = offset
                        offset += sizes[sample++]
                    }
                }
            }
            return offsets.takeIf { sample == sizes.size }
        }

        private fun decodeTimes(b: ByteArray, stts: Mp4Box, count: Int): LongArray? {
            val times = LongArray(count)
            var sample = 0
            var t = 0L
            for (run in 0 until (fits(b, stts, 8) ?: return null)) {
                val n = u32(b, stts.start + 8 + 8 * run)
                val delta = u32(b, stts.start + 12 + 8 * run)
                repeat(n) {
                    if (sample >= count) return times
                    times[sample++] = t
                    t += delta
                }
            }
            return times.takeIf { sample == count }
        }

        private fun lastDuration(b: ByteArray, stts: Mp4Box): Long {
            val runs = fits(b, stts, 8) ?: 0
            return if (runs == 0) 0 else u32(b, stts.start + 12 + 8 * (runs - 1)).toLong()
        }

        private fun compositionOffsets(b: ByteArray, ctts: Mp4Box, count: Int): LongArray {
            val signed = b[ctts.start].toInt() == 1
            val offsets = LongArray(count)
            var sample = 0
            for (run in 0 until (fits(b, ctts, 8) ?: 0)) {
                val n = u32(b, ctts.start + 8 + 8 * run)
                val raw = u32(b, ctts.start + 12 + 8 * run)
                val offset = if (signed) raw.toLong() else raw.toLong() and 0xFFFFFFFFL
                repeat(n) { if (sample < count) offsets[sample++] = offset }
            }
            return offsets
        }

        private fun syncSamples(b: ByteArray, stss: Mp4Box, count: Int): BooleanArray {
            val sync = BooleanArray(count)
            for (k in 0 until (fits(b, stss, 4) ?: 0)) {
                val sample = u32(b, stss.start + 8 + 4 * k) - 1
                if (sample in 0 until count) sync[sample] = true
            }
            return sync
        }

        // tkhd's matrix: [a b] of an unrotated video is [1 0] in 16.16 fixed point.
        private fun isRotated(b: ByteArray, trak: Mp4Box): Boolean {
            val tkhd = child(b, trak, "tkhd") ?: return false
            val matrix = tkhd.start + if (b[tkhd.start].toInt() == 1) 52 else 40
            if (matrix + 8 > tkhd.end) return false
            return u32(b, matrix) != 0x10000 || u32(b, matrix + 4) != 0
        }

        // Where the edit list puts the media on the timeline, in the track's timescale: empty edits delay it,
        // and the first real edit's media time is where presentation starts.
        private fun editListShift(b: ByteArray, trak: Mp4Box, timescale: Int, movieTimescale: Int): Long {
            val elst = child(b, trak, "edts")?.let { child(b, it, "elst") } ?: return 0
            val version = b[elst.start].toInt()
            val entrySize = if (version == 1) 20 else 12
            var delay = 0L
            for (i in 0 until (fits(b, elst, entrySize) ?: return 0)) {
                val at = elst.start + 8 + i * entrySize
                val duration = if (version == 1) readU64(b, at) else u32(b, at).toLong() and 0xFFFFFFFFL
                val mediaTime = if (version == 1) readU64(b, at + 8) else u32(b, at + 4).toLong()
                if (mediaTime == -1L) {
                    delay += duration
                    continue
                }
                return delay * timescale / movieTimescale - mediaTime
            }
            return delay * timescale / movieTimescale
        }

        private fun handlerType(b: ByteArray, trak: Mp4Box): String? {
            val hdlr = child(b, trak, "mdia")?.let { child(b, it, "hdlr") } ?: return null
            return if (hdlr.start + 12 <= hdlr.end) fourCc(b, hdlr.start + 8) else null
        }

        // A full box's entry count, when that many entries of [entrySize] fit in it after the count.
        private fun fits(b: ByteArray, box: Mp4Box, entrySize: Int): Int? {
            if (box.start + 8 > box.end) return null
            val count = u32(b, box.start + 4)
            return count.takeIf { it >= 0 && box.start + 8 + entrySize.toLong() * it <= box.end }
        }

        private fun child(b: ByteArray, parent: Mp4Box, type: String): Mp4Box? = mp4Boxes(b, parent.start, parent.end).firstOrNull { it.type == type }

        private fun u32(b: ByteArray, at: Int): Int = readU32(b, at, little = false)

    }
}

/** Each of [samples], which are in file order, with its bytes, from one read of the file. */
internal suspend fun FileOperationsProvider.readSamples(
    path: String,
    samples: List<Mp4Sample>,
    readAhead: Int = 0,
    onSample: suspend (index: Int, bytes: ByteArray) -> Unit,
) {
    var next = 0
    var position = 0L
    var pending: ByteArray? = null
    var pendingFill = 0
    val chunks = readFileAsFlow(path, READ_CHUNK).let { if (readAhead > 0) it.buffer(readAhead) else it }
    chunks.collect { chunk ->
        var at = 0
        while (at < chunk.size && next < samples.size) {
            val sample = samples[next]
            if (pending == null) {
                val skip = sample.offset - (position + at)
                if (skip > 0) {
                    at += minOf(skip, (chunk.size - at).toLong()).toInt()
                    continue
                }
                pending = ByteArray(sample.size)
                pendingFill = 0
            }
            val buffer = pending!!
            val n = minOf(buffer.size - pendingFill, chunk.size - at)
            chunk.copyInto(buffer, pendingFill, at, at + n)
            pendingFill += n
            at += n
            if (pendingFill < buffer.size) continue
            pending = null
            onSample(next++, buffer)
        }
        position += chunk.size
    }
    check(next == samples.size) { "the file ended after $next of ${samples.size} samples" }
}

private const val READ_CHUNK = 256 * 1024
