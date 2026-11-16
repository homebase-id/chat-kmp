package id.homebase.api.video.hls

import id.homebase.api.image.fourCc
import id.homebase.api.image.readU16
import id.homebase.api.image.readU32

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
    class Aac(val profile: Int, val sampleRateIndex: Int, val channels: Int) : Mp4Track()
}

/** An H.264 + AAC MP4's samples in file order, from its moov alone. Null for anything else. */
internal class Mp4Movie(val samples: List<Mp4Sample>, val end90k: Long) {
    companion object {
        fun parse(moov: ByteArray): Mp4Movie? {
            val samples = mutableListOf<Mp4Sample>()
            var end90k = 0L
            var hasVideo = false
            for (trak in children(moov, 0, moov.size).filter { it.type == "trak" }) {
                val track = parseTrack(moov, trak) ?: continue
                if (track.first.firstOrNull()?.track is Mp4Track.Avc) {
                    // MPEG-TS has no rotation: a rotated video must be turned before it's segmented.
                    if (hasVideo || isRotated(moov, trak)) return null
                    hasVideo = true
                }
                samples += track.first
                end90k = maxOf(end90k, track.second)
            }
            if (!hasVideo) return null
            samples.sortBy { it.offset }
            return Mp4Movie(samples, end90k)
        }

        // The track's samples and the 90 kHz time its last one ends. Null for a track that is neither H.264 nor AAC.
        private fun parseTrack(b: ByteArray, trak: Box): Pair<List<Mp4Sample>, Long>? {
            val mdia = child(b, trak, "mdia") ?: return null
            val mdhd = child(b, mdia, "mdhd") ?: return null
            val timescale = if (b[mdhd.start].toInt() == 1) u32(b, mdhd.start + 20) else u32(b, mdhd.start + 12)
            if (timescale <= 0) return null
            val stbl = child(b, mdia, "minf")?.let { child(b, it, "stbl") } ?: return null
            val stsd = child(b, stbl, "stsd") ?: return null
            val track = sampleEntry(b, stsd) ?: return null

            val sizes = sampleSizes(b, child(b, stbl, "stsz") ?: return null)
            val offsets = sampleOffsets(b, stbl, sizes) ?: return null
            val decodeTimes = decodeTimes(b, child(b, stbl, "stts") ?: return null, sizes.size) ?: return null
            val ctts = child(b, stbl, "ctts")?.let { compositionOffsets(b, it, sizes.size) }
            val sync = child(b, stbl, "stss")?.let { syncSamples(b, it) }
            val editShift = editListShift(b, trak)

            fun ticks(t: Long) = t * 90_000 / timescale
            val samples = sizes.indices.map { i ->
                val dts = decodeTimes[i] - editShift
                Mp4Sample(
                    track = track,
                    offset = offsets[i],
                    size = sizes[i],
                    dts90k = ticks(dts),
                    pts90k = ticks(dts + (ctts?.get(i) ?: 0)),
                    isKey = track is Mp4Track.Aac || sync == null || (i + 1) in sync,
                )
            }
            return samples to ticks(decodeTimes.last() + lastDuration(b, child(b, stbl, "stts")!!) - editShift)
        }

        private fun sampleEntry(b: ByteArray, stsd: Box): Mp4Track? {
            // A full box with an entry count, then the first entry.
            val entry = children(b, stsd.start + 8, stsd.end).firstOrNull() ?: return null
            return when (entry.type) {
                "avc1", "avc3" -> {
                    // VisualSampleEntry: 78 bytes before its child boxes.
                    val avcC = children(b, entry.start + 78, entry.end).firstOrNull { it.type == "avcC" } ?: return null
                    parseAvcC(b, avcC.start, avcC.end)
                }
                "mp4a" -> {
                    // AudioSampleEntry: 28 bytes before its child boxes.
                    val esds = children(b, entry.start + 28, entry.end).firstOrNull { it.type == "esds" } ?: return null
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
            if (audioObjectType !in 1..4 || sampleRateIndex > 12 || channels !in 1..7) return null
            return Mp4Track.Aac(audioObjectType - 1, sampleRateIndex, channels)
        }

        private fun sampleSizes(b: ByteArray, stsz: Box): IntArray {
            val fixed = u32(b, stsz.start + 4)
            val count = u32(b, stsz.start + 8)
            return IntArray(count) { if (fixed != 0) fixed else u32(b, stsz.start + 12 + 4 * it) }
        }

        private fun sampleOffsets(b: ByteArray, stbl: Box, sizes: IntArray): LongArray? {
            val stco = child(b, stbl, "stco")
            val co64 = child(b, stbl, "co64")
            val chunkOffsets = when {
                stco != null -> LongArray(u32(b, stco.start + 4)) { u32(b, stco.start + 8 + 4 * it).toLong() and 0xFFFFFFFFL }
                co64 != null -> LongArray(u32(b, co64.start + 4)) { u64(b, co64.start + 8 + 8 * it) }
                else -> return null
            }
            val stsc = child(b, stbl, "stsc") ?: return null
            val runs = u32(b, stsc.start + 4)
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

        private fun decodeTimes(b: ByteArray, stts: Box, count: Int): LongArray? {
            val times = LongArray(count)
            var sample = 0
            var t = 0L
            for (run in 0 until u32(b, stts.start + 4)) {
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

        private fun lastDuration(b: ByteArray, stts: Box): Long {
            val runs = u32(b, stts.start + 4)
            return if (runs == 0) 0 else u32(b, stts.start + 12 + 8 * (runs - 1)).toLong()
        }

        private fun compositionOffsets(b: ByteArray, ctts: Box, count: Int): LongArray {
            val signed = b[ctts.start].toInt() == 1
            val offsets = LongArray(count)
            var sample = 0
            for (run in 0 until u32(b, ctts.start + 4)) {
                val n = u32(b, ctts.start + 8 + 8 * run)
                val raw = u32(b, ctts.start + 12 + 8 * run)
                val offset = if (signed) raw.toLong() else raw.toLong() and 0xFFFFFFFFL
                repeat(n) { if (sample < count) offsets[sample++] = offset }
            }
            return offsets
        }

        private fun syncSamples(b: ByteArray, stss: Box): Set<Int> =
            (0 until u32(b, stss.start + 4)).mapTo(HashSet()) { u32(b, stss.start + 8 + 4 * it) }

        // tkhd's matrix: [a b] of an unrotated video is [1 0] in 16.16 fixed point.
        private fun isRotated(b: ByteArray, trak: Box): Boolean {
            val tkhd = child(b, trak, "tkhd") ?: return false
            val matrix = tkhd.start + if (b[tkhd.start].toInt() == 1) 52 else 40
            if (matrix + 8 > tkhd.end) return false
            return u32(b, matrix) != 0x10000 || u32(b, matrix + 4) != 0
        }

        // The first edit's media time: where presentation starts, in the track's timescale.
        private fun editListShift(b: ByteArray, trak: Box): Long {
            val elst = child(b, trak, "edts")?.let { child(b, it, "elst") } ?: return 0
            val version = b[elst.start].toInt()
            if (u32(b, elst.start + 4) == 0) return 0
            val mediaTime = if (version == 1) u64(b, elst.start + 16) else u32(b, elst.start + 12).toLong()
            return mediaTime.coerceAtLeast(0)
        }

        private class Box(val type: String, val start: Int, val end: Int)

        private fun children(b: ByteArray, from: Int, until: Int): List<Box> {
            val boxes = mutableListOf<Box>()
            var i = from
            while (i + 8 <= until) {
                var size = u32(b, i).toLong() and 0xFFFFFFFFL
                var header = 8
                if (size == 1L && i + 16 <= until) {
                    size = u64(b, i + 8)
                    header = 16
                }
                if (size < header || i + size > until) break
                boxes += Box(fourCc(b, i + 4), i + header, (i + size).toInt())
                i += size.toInt()
            }
            return boxes
        }

        private fun child(b: ByteArray, parent: Box, type: String): Box? = children(b, parent.start, parent.end).firstOrNull { it.type == type }

        private fun u32(b: ByteArray, at: Int): Int = readU32(b, at, little = false)

        private fun u64(b: ByteArray, at: Int): Long =
            ((u32(b, at).toLong() and 0xFFFFFFFFL) shl 32) or (u32(b, at + 4).toLong() and 0xFFFFFFFFL)
    }
}
