package id.homebase.core.audio

import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

class AudioFileInfo(
    private val durationUs: Long,
    waveFormBytes: ByteArray,
) {
    val waveForm: FloatArray = FloatArray(waveFormBytes.size)

    init {
        for (i in waveFormBytes.indices) {
            val unsigned = waveFormBytes[i].toInt() and 0xff
            this.waveForm[i] = unsigned / 255f
        }
    }

    fun getDuration(): Duration {
        return durationUs.microseconds
    }
}