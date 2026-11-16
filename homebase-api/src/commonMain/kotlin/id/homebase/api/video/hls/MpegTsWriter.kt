package id.homebase.api.video.hls

/**
 * Writes H.264 and AAC samples as MPEG-TS, laid out as ffmpeg's mpegts muxer does: PMT on 0x1000,
 * video on 0x100 carrying the PCR, audio on 0x101, timestamps offset by 1.4 s.
 */
internal class MpegTsWriter(private val video: Mp4Track.Avc, private val audio: Mp4Track.Aac?) {
    private val out = ByteBuilder()
    private val continuity = IntArray(0x2000)
    private val pesHeader = ByteArray(19)

    /** Starts a segment: players need the program tables before its first frame. */
    fun writeTables() {
        writeSection(PAT_PID, pat())
        writeSection(PMT_PID, pmt())
    }

    fun write(sample: Mp4Sample, data: ByteArray) {
        when (val track = sample.track) {
            is Mp4Track.Avc -> writePes(
                VIDEO_PID, 0xE0, annexB(track, sample.isKey, data),
                pts = sample.pts90k + OFFSET_90K, dts = sample.dts90k + OFFSET_90K,
                pcr = sample.dts90k + OFFSET_90K - PCR_LEAD_90K, randomAccess = sample.isKey,
            )
            is Mp4Track.Aac -> writePes(
                AUDIO_PID, 0xC0, adts(track, data),
                pts = sample.pts90k + OFFSET_90K, dts = null, pcr = null, randomAccess = false,
            )
        }
    }

    /** The bytes written since the last call. */
    fun drain(): ByteArray = out.take()

    // Ours go first: an access unit delimiter, and on keyframes the parameter sets.
    private fun keep(type: Int, isKey: Boolean) = type != NAL_AUD && !(isKey && (type == NAL_SPS || type == NAL_PPS))

    private fun annexB(track: Mp4Track.Avc, isKey: Boolean, sample: ByteArray): ByteArray {
        val sets = if (isKey) track.sps + track.pps else emptyList()
        var size = START_CODE.size + AUD.size + sets.sumOf { START_CODE.size + it.size }
        forEachNal(track, sample) { at, length -> if (keep(sample[at].toInt() and 0x1F, isKey)) size += START_CODE.size + length }
        val b = ByteArray(size)
        var to = 0
        fun put(data: ByteArray, from: Int = 0, length: Int = data.size) {
            data.copyInto(b, to, from, from + length)
            to += length
        }
        put(START_CODE); put(AUD)
        sets.forEach { put(START_CODE); put(it) }
        forEachNal(track, sample) { at, length ->
            if (keep(sample[at].toInt() and 0x1F, isKey)) {
                put(START_CODE)
                put(sample, at, length)
            }
        }
        return b
    }

    private inline fun forEachNal(track: Mp4Track.Avc, sample: ByteArray, block: (at: Int, length: Int) -> Unit) {
        var at = 0
        while (at + track.nalLengthSize <= sample.size) {
            var length = 0
            repeat(track.nalLengthSize) { length = (length shl 8) or (sample[at++].toInt() and 0xFF) }
            if (length <= 0 || at + length > sample.size) return
            block(at, length)
            at += length
        }
    }

    private fun adts(track: Mp4Track.Aac, frame: ByteArray): ByteArray {
        val length = frame.size + 7
        val b = ByteArray(length)
        b[0] = 0xFF.toByte()
        b[1] = 0xF1.toByte()
        b[2] = ((track.profile shl 6) or (track.sampleRateIndex shl 2) or (track.channels shr 2)).toByte()
        b[3] = (((track.channels and 3) shl 6) or (length shr 11)).toByte()
        b[4] = ((length shr 3) and 0xFF).toByte()
        b[5] = (((length and 7) shl 5) or 0x1F).toByte()
        b[6] = 0xFC.toByte()
        frame.copyInto(b, 7)
        return b
    }

    private fun writePes(pid: Int, streamId: Int, payload: ByteArray, pts: Long, dts: Long?, pcr: Long?, randomAccess: Boolean) {
        val withDts = dts != null && dts != pts
        val headerData = if (withDts) 10 else 5
        val length = 3 + headerData + payload.size
        // Video may exceed 16 bits; 0 means "unbounded", as ffmpeg writes for video.
        val pesLength = if (pid == VIDEO_PID || length > 0xFFFF) 0 else length
        val h = pesHeader
        h[0] = 0; h[1] = 0; h[2] = 1; h[3] = streamId.toByte()
        h[4] = (pesLength shr 8).toByte(); h[5] = pesLength.toByte()
        h[6] = 0x84.toByte(); h[7] = (if (withDts) 0xC0 else 0x80).toByte(); h[8] = headerData.toByte()
        timestamp(h, 9, if (withDts) 3 else 2, pts)
        if (withDts) timestamp(h, 14, 1, dts!!)
        packetize(pid, h, 9 + headerData, payload, pcr, randomAccess)
    }

    private fun timestamp(b: ByteArray, at: Int, marker: Int, t: Long) {
        val v = t and 0x1FFFFFFFFL
        b[at] = ((marker shl 4) or ((v shr 29).toInt() and 0x0E) or 1).toByte()
        b[at + 1] = (v shr 22).toByte()
        b[at + 2] = (((v shr 14).toInt() and 0xFE) or 1).toByte()
        b[at + 3] = (v shr 7).toByte()
        b[at + 4] = (((v shl 1).toInt() and 0xFE) or 1).toByte()
    }

    // The PES header then its payload, cut into 188-byte packets written straight to the output.
    private fun packetize(pid: Int, header: ByteArray, headerSize: Int, payload: ByteArray, pcr: Long?, randomAccess: Boolean) {
        val total = headerSize + payload.size
        var at = 0
        var first = true
        while (at < total) {
            val flags = first && (pcr != null || randomAccess)
            val fieldBody = if (flags) 1 + (if (pcr != null) 6 else 0) else 0
            val room = PACKET - 4 - (if (fieldBody > 0) 1 + fieldBody else 0)
            val n = minOf(room, total - at)
            // The last packet is stuffed through the adaptation field.
            val stuffing = room - n
            val fieldLength = when {
                fieldBody > 0 -> fieldBody + stuffing
                stuffing == 0 -> -1
                else -> stuffing - 1
            }
            out.addByte(0x47)
            out.addByte((if (first) 0x40 else 0) or (pid shr 8))
            out.addByte(pid)
            out.addByte((if (fieldLength >= 0) 0x30 else 0x10) or next(pid))
            if (fieldLength >= 0) {
                out.addByte(fieldLength)
                if (fieldLength > 0) {
                    out.addByte(if (flags) (if (randomAccess) 0x40 else 0) or (if (pcr != null) 0x10 else 0) else 0)
                    if (flags && pcr != null) {
                        val base = pcr.coerceAtLeast(0) and 0x1FFFFFFFFL
                        out.addByte((base shr 25).toInt()); out.addByte((base shr 17).toInt()); out.addByte((base shr 9).toInt())
                        out.addByte((base shr 1).toInt()); out.addByte((((base and 1) shl 7) or 0x7E).toInt()); out.addByte(0)
                    }
                    repeat(fieldLength - (if (fieldBody > 0) fieldBody else 1)) { out.addByte(0xFF) }
                }
            }
            val fromHeader = minOf(n, (headerSize - at).coerceAtLeast(0))
            if (fromHeader > 0) out.add(header, at, fromHeader)
            if (n > fromHeader) out.add(payload, at + fromHeader - headerSize, n - fromHeader)
            at += n
            first = false
        }
    }

    private fun writeSection(pid: Int, section: ByteArray) {
        val packet = ByteArray(PACKET) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or (pid shr 8)).toByte()
        packet[2] = pid.toByte()
        packet[3] = (0x10 or next(pid)).toByte()
        packet[4] = 0
        section.copyInto(packet, 5)
        out.add(packet)
    }

    private fun pat(): ByteArray = psi(0x00, 1, byteArrayOf(0, 1, (0xE0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte()))

    private fun pmt(): ByteArray {
        val video = byteArrayOf(0x1B, (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xF0.toByte(), 0)
        val audio = if (audio == null) byteArrayOf() else byteArrayOf(0x0F, (0xE0 or (AUDIO_PID shr 8)).toByte(), AUDIO_PID.toByte(), 0xF0.toByte(), 0)
        return psi(0x02, 1, byteArrayOf((0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xF0.toByte(), 0) + video + audio)
    }

    private fun psi(tableId: Int, idExtension: Int, body: ByteArray): ByteArray {
        val length = 5 + body.size + 4
        val section = byteArrayOf(
            tableId.toByte(), (0xB0 or (length shr 8)).toByte(), length.toByte(),
            (idExtension shr 8).toByte(), idExtension.toByte(), 0xC1.toByte(), 0, 0,
        ) + body
        val crc = crc32(section)
        return section + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    private fun next(pid: Int): Int = continuity[pid].also { continuity[pid] = (it + 1) and 0xF }

    private companion object {
        const val PACKET = 188
        const val PAT_PID = 0x0000
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x0100
        const val AUDIO_PID = 0x0101
        const val OFFSET_90K = 126_000L
        const val PCR_LEAD_90K = 63_000L
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val NAL_AUD = 9
        val START_CODE = byteArrayOf(0, 0, 0, 1)
        val AUD = byteArrayOf(0x09, 0xF0.toByte())

        // CRC-32/MPEG-2.
        fun crc32(data: ByteArray): Int {
            var crc = -1
            for (byte in data) {
                crc = crc xor ((byte.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}

/** A growable byte buffer that keeps its capacity between [take]s. */
internal class ByteBuilder {
    private var bytes = ByteArray(1024 * 1024)
    private var size = 0

    private fun room(n: Int) {
        if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
    }

    fun add(data: ByteArray, from: Int = 0, length: Int = data.size - from) {
        room(length)
        data.copyInto(bytes, size, from, from + length)
        size += length
    }

    fun addByte(value: Int) {
        room(1)
        bytes[size++] = value.toByte()
    }

    fun take(): ByteArray = bytes.copyOf(size).also { size = 0 }
}
