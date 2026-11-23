package id.homebase.chat.services.calling

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.NSLock
import platform.Foundation.create

actual fun platformRingbackTone(): RingbackTone = AppleRingbackTone()

@OptIn(ExperimentalForeignApi::class)
private class AppleRingbackTone : RingbackTone {
    private val lock = NSLock()
    private var player: AVAudioPlayer? = null

    override fun start() = withLock {
        if (player != null) return@withLock
        val wav = ringbackWav()
        val data = wav.usePinned { NSData.create(bytes = it.addressOf(0), length = wav.size.toULong()) }
        player = AVAudioPlayer(data = data, error = null).apply {
            numberOfLoops = -1
            prepareToPlay()
            play()
        }
    }

    override fun stop() = withLock {
        player?.stop()
        player = null
    }

    private inline fun withLock(block: () -> Unit) {
        lock.lock()
        try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
