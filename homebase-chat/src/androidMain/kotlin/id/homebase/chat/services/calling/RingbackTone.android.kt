package id.homebase.chat.services.calling

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

actual fun platformRingbackTone(): RingbackTone = AndroidRingbackTone()

private class AndroidRingbackTone : RingbackTone {
    private var track: AudioTrack? = null

    @Synchronized
    override fun start() {
        if (track != null) return
        val wav = ringbackWav()
        val pcm = wav.size - 44
        val built = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(8_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm)
                .build()
        }.getOrNull() ?: return
        track = runCatching {
            built.apply {
                write(wav, 44, pcm)
                setLoopPoints(0, pcm / 2, -1)
                play()
            }
        }.onFailure { built.release() }.getOrNull()
    }

    @Synchronized
    override fun stop() {
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
    }
}
