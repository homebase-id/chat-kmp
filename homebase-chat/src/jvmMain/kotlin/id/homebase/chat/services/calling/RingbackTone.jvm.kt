package id.homebase.chat.services.calling

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

actual fun platformRingbackTone(): RingbackTone = JvmRingbackTone()

internal class JvmRingbackTone(
    private val newClip: () -> Clip = { AudioSystem.getClip() },
) : RingbackTone {
    private var clip: Clip? = null

    @Synchronized
    override fun start() {
        if (clip != null) return
        val opened = runCatching { newClip() }.getOrNull() ?: return
        clip = runCatching {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(ringbackWav())).use { opened.open(it) }
            opened.loop(Clip.LOOP_CONTINUOUSLY)
            opened
        }.getOrElse {
            runCatching { opened.close() }
            null
        }
    }

    @Synchronized
    override fun stop() {
        clip?.run {
            stop()
            close()
        }
        clip = null
    }
}
