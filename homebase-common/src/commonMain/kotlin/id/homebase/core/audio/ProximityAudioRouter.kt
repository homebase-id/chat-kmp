package id.homebase.core.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Raise-to-ear for voice-note playback: while [start]ed, the device's proximity sensor
 * moves audio between the loudspeaker and the earpiece and blanks the screen when covered.
 *
 * [start] and [stop] are idempotent — they are driven by playback lifecycle and will be
 * called repeatedly.
 */
interface ProximityAudioRouter {
    val isNearEar: StateFlow<Boolean>

    fun start()

    fun stop()
}

expect fun getProximityAudioRouter(): ProximityAudioRouter
