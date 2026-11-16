package id.homebase.api.video.hls

import id.homebase.api.util.ByteBuilder
import id.homebase.api.video.hls.MpegTs.AAC_FRAME

/** The samples [TsToMp4] will write, in order (sizes only), and the `ftyp` + `moov` + `mdat` header that indexes them. */
internal class SampleTable {
    private val entries = ArrayList<TsSample>()

    fun add(sample: TsSample) {
        entries += sample
    }

    fun header(avc: Mp4Track.Avc, aac: Mp4Track.Aac?): ByteArray {
        val video = entries.filter { it.video }
        val audio = entries.filter { !it.video }
        check(video.isNotEmpty()) { "no H.264 frames" }
        // Tracks line up by when their first sample shows, not when it decodes.
        val base = minOf(video.first().pts, audio.firstOrNull()?.pts ?: Long.MAX_VALUE)
        val sps = readSps(avc.sps.first()) ?: error("unreadable SPS")
        val (width, height) = sps.width to sps.height
        val ftyp = Boxes().apply {
            box("ftyp") { type("isom"); u32(0x200); type("isom"); type("iso2"); type("avc1"); type("mp41") }
        }.bytes()
        val mdatBytes = entries.sumOf { it.size.toLong() }
        val mdatHeader = if (mdatBytes + 8 > 0xFFFFFFFFL) 16 else 8
        // The offsets depend on the moov's size, which doesn't depend on their values: build it twice.
        val placeholder = moov(video, audio, avc, sps, aac, width, height, base, LongArray(video.size), LongArray(audio.size), wide = false)
        val wide = ftyp.size + placeholder.size + mdatHeader + mdatBytes > 0xFFFFFFFFL
        val first = ftyp.size + placeholder.size + (if (wide) 4L * entries.size else 0L) + mdatHeader
        val videoOffsets = LongArray(video.size)
        val audioOffsets = LongArray(audio.size)
        var at = first
        var v = 0
        var a = 0
        for (e in entries) {
            if (e.video) videoOffsets[v++] = at else audioOffsets[a++] = at
            at += e.size
        }
        val moov = moov(video, audio, avc, sps, aac, width, height, base, videoOffsets, audioOffsets, wide)
        val mdat = Boxes().apply {
            if (mdatHeader == 16) { u32(1); type("mdat"); u64(mdatBytes + 16) } else { u32(mdatBytes + 8); type("mdat") }
        }.bytes()
        return ftyp + moov + mdat
    }

    private fun moov(
        video: List<TsSample>, audio: List<TsSample>, avc: Mp4Track.Avc, sps: Sps, aac: Mp4Track.Aac?, width: Int, height: Int,
        base: Long, videoOffsets: LongArray, audioOffsets: LongArray, wide: Boolean,
    ): ByteArray = Boxes().apply {
        val videoDurations = LongArray(video.size) { i ->
            if (i + 1 < video.size) video[i + 1].dts - video[i].dts else if (i > 0) video[i].dts - video[i - 1].dts else 3000
        }
        val videoTicks = videoDurations.sum()
        val videoStartMs = (video.first().pts - base) / 90
        val rate = aac?.sampleRate ?: 1
        val audioTicks = audio.size.toLong() * AAC_FRAME
        val audioStartMs = audio.firstOrNull()?.let { (it.pts - base) / 90 } ?: 0
        val movieMs = maxOf(videoStartMs + videoTicks / 90, if (audio.isEmpty()) 0 else audioStartMs + audioTicks * 1000 / rate)
        box("moov") {
            full("mvhd", 0, 0) {
                u32(0); u32(0); u32(1000); u32(movieMs); u32(0x00010000); u16(0x0100); repeat(10) { u8(0) }
                matrix(); repeat(6) { u32(0) }; u32(if (audio.isEmpty()) 2 else 3)
            }
            track(1, "vide", 90_000, videoTicks, videoStartMs, video.first().pts - video.first().dts, width, height) {
                full("stsd", 0, 0) {
                    u32(1)
                    box("avc1") {
                        repeat(6) { u8(0) }; u16(1); u16(0); u16(0); repeat(3) { u32(0) }; u16(width); u16(height)
                        u32(0x00480000); u32(0x00480000); u32(0); u16(1); repeat(32) { u8(0) }; u16(0x18); u16(0xFFFF)
                        box("avcC") {
                            val first = avc.sps.first()
                            u8(1); u8(first[1].toInt()); u8(first[2].toInt()); u8(first[3].toInt()); u8(0xFF)
                            u8(0xE0 or avc.sps.size); avc.sps.forEach { u16(it.size); bytes(it) }
                            u8(avc.pps.size); avc.pps.forEach { u16(it.size); bytes(it) }
                            // ISO/IEC 14496-15 adds the chroma format and bit depths for the High profiles.
                            if (sps.profile in HIGH_AVCC_PROFILES) {
                                u8(0xFC or sps.chromaFormat); u8(0xF8 or (sps.bitDepthLuma - 8)); u8(0xF8 or (sps.bitDepthChroma - 8)); u8(0)
                            }
                        }
                    }
                }
                runs("stts", videoDurations.size) { videoDurations[it] }
                val offsets = LongArray(video.size) { video[it].pts - video[it].dts }
                if (offsets.any { it != 0L }) runs("ctts", offsets.size) { offsets[it] }
                full("stss", 0, 0) {
                    val keys = video.indices.filter { video[it].isKey }
                    u32(keys.size); keys.forEach { u32(it + 1) }
                }
                chunks(video.map { it.size }, videoOffsets, wide)
            }
            if (aac != null && audio.isNotEmpty()) {
                track(2, "soun", rate, audioTicks, audioStartMs, 0, 0, 0) {
                    full("stsd", 0, 0) {
                        u32(1)
                        box("mp4a") {
                            repeat(6) { u8(0) }; u16(1); repeat(8) { u8(0) }; u16(aac.channels); u16(16); u16(0); u16(0); u32(rate.toLong() shl 16)
                            full("esds", 0, 0) {
                                val config = ((aac.profile + 1) shl 11) or (aac.sampleRateIndex shl 7) or (aac.channels shl 3)
                                u8(0x03); u8(25); u16(0); u8(0)
                                u8(0x04); u8(17); u8(0x40); u8(0x15); u8(0); u16(0); u32(0); u32(0)
                                u8(0x05); u8(2); u16(config)
                                u8(0x06); u8(1); u8(0x02)
                            }
                        }
                    }
                    runs("stts", audio.size) { AAC_FRAME.toLong() }
                    chunks(audio.map { it.size }, audioOffsets, wide)
                }
            }
        }
    }.bytes()

    private fun Boxes.track(
        id: Int, handler: String, timescale: Int, ticks: Long, startMs: Long, mediaStart: Long, width: Int, height: Int,
        stbl: Boxes.() -> Unit,
    ) = box("trak") {
        // A first frame shown late (B-frames) is skipped to; the frames shown still span the whole duration.
        val durationMs = ticks * 1000 / timescale
        full("tkhd", 0, 3) {
            u32(0); u32(0); u32(id); u32(0); u32(startMs + durationMs); u32(0); u32(0)
            u16(0); u16(0); u16(if (handler == "soun") 0x0100 else 0); u16(0); matrix(); u32(width.toLong() shl 16); u32(height.toLong() shl 16)
        }
        // A track that starts after the movie waits in an empty edit; a first frame shown late (B-frames) is skipped to.
        if (startMs > 0 || mediaStart > 0) box("edts") {
            full("elst", 0, 0) {
                u32(if (startMs > 0) 2 else 1)
                if (startMs > 0) { u32(startMs); u32(0xFFFFFFFFL); u32(0x00010000) }
                u32(durationMs); u32(mediaStart); u32(0x00010000)
            }
        }
        box("mdia") {
            full("mdhd", 0, 0) { u32(0); u32(0); u32(timescale.toLong()); u32(ticks); u16(0x55C4); u16(0) }
            full("hdlr", 0, 0) { u32(0); type(handler); repeat(3) { u32(0) }; bytes(if (handler == "vide") "VideoHandler\u0000".encodeToByteArray() else "SoundHandler\u0000".encodeToByteArray()) }
            box("minf") {
                if (handler == "vide") full("vmhd", 0, 1) { repeat(4) { u16(0) } } else full("smhd", 0, 0) { u16(0); u16(0) }
                box("dinf") { full("dref", 0, 0) { u32(1); full("url ", 0, 1) {} } }
                box("stbl") { stbl() }
            }
        }
    }

    /** stts or ctts: runs of equal values. */
    private fun Boxes.runs(type: String, count: Int, value: (Int) -> Long) = full(type, 0, 0) {
        val counts = ArrayList<Long>()
        val values = ArrayList<Long>()
        for (i in 0 until count) {
            val v = value(i)
            if (values.isNotEmpty() && values.last() == v) counts[counts.size - 1] = counts.last() + 1 else { counts += 1L; values += v }
        }
        u32(counts.size.toLong())
        for (i in counts.indices) { u32(counts[i]); u32(values[i]) }
    }

    // One sample a chunk: samples are written in the TS's own interleaved order.
    private fun Boxes.chunks(sizes: List<Int>, offsets: LongArray, wide: Boolean) {
        full("stsc", 0, 0) { u32(1); u32(1); u32(1); u32(1) }
        full("stsz", 0, 0) { u32(0); u32(sizes.size); sizes.forEach { u32(it) } }
        if (wide) full("co64", 0, 0) { u32(offsets.size); offsets.forEach { u64(it) } }
        else full("stco", 0, 0) { u32(offsets.size); offsets.forEach { u32(it) } }
    }

    private fun Boxes.matrix() = listOf(0x00010000L, 0, 0, 0, 0x00010000L, 0, 0, 0, 0x40000000L).forEach { u32(it) }
}

/** A big-endian box writer: [box] patches each box's size in after its body. */
internal class Boxes {
    private val out = ByteBuilder(4096)

    fun u8(v: Int) = out.addByte(v)
    fun u16(v: Int) { out.addByte(v shr 8); out.addByte(v) }
    fun u32(v: Int) = u32(v.toLong())
    fun u32(v: Long) = out.addU32(v)
    fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
    fun bytes(x: ByteArray) = out.add(x)
    fun type(t: String) = t.forEach { u8(it.code) }

    fun box(type: String, body: Boxes.() -> Unit) {
        val at = out.size
        u32(0)
        type(type)
        body()
        out.setU32(at, (out.size - at).toLong())
    }

    fun full(type: String, version: Int, flags: Int, body: Boxes.() -> Unit) = box(type) { u32((version shl 24) or flags); body() }

    fun bytes(): ByteArray = out.take()
}

/** What the MP4 index needs from an H.264 SPS: the cropped frame size, and the High profiles' format fields. */
internal class Sps(val profile: Int, val width: Int, val height: Int, val chromaFormat: Int, val bitDepthLuma: Int, val bitDepthChroma: Int)

private val HIGH_AVCC_PROFILES = setOf(100, 110, 122, 144)

/** Null when [sps] can't be read. */
internal fun readSps(sps: ByteArray): Sps? = try {
    val r = ExpGolomb(sps)
    r.bits(8) // NAL header
    val profile = r.bits(8)
    r.bits(16)
    r.ue()
    var chroma = 1
    var lumaDepth = 8
    var chromaDepth = 8
    if (profile in HIGH_PROFILES) {
        chroma = r.ue()
        if (chroma == 3) r.bits(1)
        lumaDepth = 8 + r.ue()
        chromaDepth = 8 + r.ue()
        r.bits(1)
        if (r.bits(1) == 1) {
            for (i in 0 until if (chroma != 3) 8 else 12) {
                if (r.bits(1) == 1) {
                    var last = 8
                    var next = 8
                    repeat(if (i < 6) 16 else 64) {
                        if (next != 0) next = (last + r.se() + 256) % 256
                        if (next != 0) last = next
                    }
                }
            }
        }
    }
    r.ue()
    when (r.ue()) {
        0 -> r.ue()
        1 -> { r.bits(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
    }
    r.ue(); r.bits(1)
    val widthMbs = r.ue() + 1
    val heightUnits = r.ue() + 1
    val frameMbsOnly = r.bits(1)
    if (frameMbsOnly == 0) r.bits(1)
    r.bits(1)
    var width = widthMbs * 16
    var height = (2 - frameMbsOnly) * heightUnits * 16
    if (r.bits(1) == 1) {
        val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
        val unitX = if (chroma == 1 || chroma == 2) 2 else 1
        val unitY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
        width -= (left + right) * unitX
        height -= (top + bottom) * unitY
    }
    Sps(profile, width, height, chroma, lumaDepth, chromaDepth)
} catch (_: IndexOutOfBoundsException) {
    null
}

private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

/** Bits of a NAL with its emulation-prevention bytes removed. */
private class ExpGolomb(nal: ByteArray) {
    private val b: ByteArray = ByteArray(nal.size).let { out ->
        var n = 0
        var zeros = 0
        for (x in nal) {
            if (zeros >= 2 && x.toInt() == 3) { zeros = 0; continue }
            out[n++] = x
            zeros = if (x.toInt() == 0) zeros + 1 else 0
        }
        out.copyOf(n)
    }
    private var bit = 0

    fun bits(count: Int): Int {
        var v = 0
        repeat(count) {
            v = (v shl 1) or ((b[bit ushr 3].toInt() shr (7 - (bit and 7))) and 1)
            bit++
        }
        return v
    }

    fun ue(): Int {
        var zeros = 0
        while (bits(1) == 0) zeros++
        return (1 shl zeros) - 1 + bits(zeros)
    }

    fun se(): Int = ue().let { if (it and 1 == 1) (it + 1) / 2 else -(it / 2) }
}
