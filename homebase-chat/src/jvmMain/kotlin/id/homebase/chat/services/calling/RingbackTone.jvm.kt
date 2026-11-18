package id.homebase.chat.services.calling

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

actual fun platformRingbackTone(): RingbackTone = JvmRingbackTone()

private class JvmRingbackTone : RingbackTone {
    private var clip: Clip? = null

    @Synchronized
    override fun start() {
        if (clip != null) return
        clip = runCatching {
            AudioSystem.getClip().apply {
                AudioSystem.getAudioInputStream(ByteArrayInputStream(ringbackWav())).use { open(it) }
                loop(Clip.LOOP_CONTINUOUSLY)
            }
        }.getOrNull()
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
