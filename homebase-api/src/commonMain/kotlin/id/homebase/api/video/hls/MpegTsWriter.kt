package id.homebase.api.video.hls

/**
 * Writes H.264 and AAC samples as MPEG-TS, laid out as ffmpeg's mpegts muxer does: PMT on 0x1000,
 * video on 0x100 carrying the PCR, audio on 0x101, timestamps offset by 1.4 s.
 */
internal class MpegTsWriter(private val video: Mp4Track.Avc, private val audio: Mp4Track.Aac?) {
    private val out = ByteBuilder()
    private val continuity = IntArray(0x2000)

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

    private fun annexB(track: Mp4Track.Avc, isKey: Boolean, sample: ByteArray): ByteArray {
        val b = ByteBuilder()
        b.add(START_CODE); b.add(AUD)
        if (isKey) (track.sps + track.pps).forEach { b.add(START_CODE); b.add(it) }
        var at = 0
        while (at + track.nalLengthSize <= sample.size) {
            var length = 0
            repeat(track.nalLengthSize) { length = (length shl 8) or (sample[at++].toInt() and 0xFF) }
            if (length <= 0 || at + length > sample.size) break
            val type = sample[at].toInt() and 0x1F
            // Ours go first: an access unit delimiter, and on keyframes the parameter sets.
            if (type != NAL_AUD && !(isKey && (type == NAL_SPS || type == NAL_PPS))) {
                b.add(START_CODE)
                b.add(sample, at, length)
            }
            at += length
        }
        return b.take()
    }

    private fun adts(track: Mp4Track.Aac, frame: ByteArray): ByteArray {
        val length = frame.size + 7
        val header = byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((track.profile shl 6) or (track.sampleRateIndex shl 2) or (track.channels shr 2)).toByte(),
            (((track.channels and 3) shl 6) or (length shr 11)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return header + frame
    }

    private fun writePes(pid: Int, streamId: Int, payload: ByteArray, pts: Long, dts: Long?, pcr: Long?, randomAccess: Boolean) {
        val withDts = dts != null && dts != pts
        val headerData = if (withDts) 10 else 5
        val pes = ByteBuilder()
        pes.add(byteArrayOf(0, 0, 1, streamId.toByte()))
        val length = 3 + headerData + payload.size
        // Video may exceed 16 bits; 0 means "unbounded", as ffmpeg writes for video.
        val pesLength = if (pid == VIDEO_PID || length > 0xFFFF) 0 else length
        pes.add(byteArrayOf((pesLength shr 8).toByte(), pesLength.toByte(), 0x84.toByte(), (if (withDts) 0xC0 else 0x80).toByte(), headerData.toByte()))
        pes.add(timestamp(if (withDts) 3 else 2, pts))
        if (withDts) pes.add(timestamp(1, dts!!))
        pes.add(payload)
        packetize(pid, pes.take(), pcr, randomAccess)
    }

    private fun timestamp(marker: Int, t: Long): ByteArray {
        val v = t and 0x1FFFFFFFFL
        return byteArrayOf(
            ((marker shl 4) or ((v shr 29).toInt() and 0x0E) or 1).toByte(),
            (v shr 22).toByte(),
            (((v shr 14).toInt() and 0xFE) or 1).toByte(),
            (v shr 7).toByte(),
            (((v shl 1).toInt() and 0xFE) or 1).toByte(),
        )
    }

    private fun packetize(pid: Int, data: ByteArray, pcr: Long?, randomAccess: Boolean) {
        var at = 0
        var first = true
        while (at < data.size) {
            val adaptation = ByteBuilder()
            if (first && (pcr != null || randomAccess)) {
                adaptation.add(byteArrayOf(((if (randomAccess) 0x40 else 0) or (if (pcr != null) 0x10 else 0)).toByte()))
                if (pcr != null) {
                    val base = pcr.coerceAtLeast(0) and 0x1FFFFFFFFL
                    adaptation.add(byteArrayOf((base shr 25).toByte(), (base shr 17).toByte(), (base shr 9).toByte(), (base shr 1).toByte(), (((base and 1) shl 7) or 0x7E).toInt().toByte(), 0))
                }
            }
            var fieldLength = adaptation.size
            var room = PACKET - 4 - (if (fieldLength > 0) 1 + fieldLength else 0)
            val remaining = data.size - at
            if (remaining < room) {
                // Stuff the last packet through the adaptation field.
                val stuffing = room - remaining
                if (fieldLength == 0) {
                    if (stuffing == 1) {
                        fieldLength = -1
                    } else {
                        adaptation.add(byteArrayOf(0))
                        adaptation.add(ByteArray(stuffing - 2) { 0xFF.toByte() })
                        fieldLength = adaptation.size
                    }
                } else {
                    adaptation.add(ByteArray(stuffing) { 0xFF.toByte() })
                    fieldLength = adaptation.size
                }
                room = remaining
            }
            val hasField = fieldLength != 0
            out.add(byteArrayOf(0x47, ((if (first) 0x40 else 0) or (pid shr 8)).toByte(), pid.toByte(), ((if (hasField) 0x30 else 0x10) or next(pid)).toByte()))
            if (fieldLength == -1) out.add(byteArrayOf(0)) else if (hasField) {
                out.add(byteArrayOf(fieldLength.toByte()))
                out.add(adaptation.take())
            }
            out.add(data, at, room)
            at += room
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
        val body = ByteBuilder()
        body.add(byteArrayOf((0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xF0.toByte(), 0))
        body.add(byteArrayOf(0x1B, (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xF0.toByte(), 0))
        if (audio != null) body.add(byteArrayOf(0x0F, (0xE0 or (AUDIO_PID shr 8)).toByte(), AUDIO_PID.toByte(), 0xF0.toByte(), 0))
        return psi(0x02, 1, body.take())
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

/** A growable byte buffer. */
internal class ByteBuilder {
    private var bytes = ByteArray(64 * 1024)
    var size = 0
        private set

    fun add(data: ByteArray, from: Int = 0, length: Int = data.size - from) {
        if (size + length > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + length))
        data.copyInto(bytes, size, from, from + length)
        size += length
    }

    fun take(): ByteArray = bytes.copyOf(size).also { size = 0 }
}
