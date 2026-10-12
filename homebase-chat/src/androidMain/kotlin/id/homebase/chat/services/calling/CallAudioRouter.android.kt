package id.homebase.chat.services.calling

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.koin.mp.KoinPlatformTools

actual class CallAudioRouter actual constructor() {
    private val audio: AudioManager =
        KoinPlatformTools.defaultContext().get().get<Context>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL

    actual fun start() {
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setRoute(AudioRoute.Earpiece)
    }

    actual fun setRoute(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = when (route) {
                AudioRoute.Earpiece -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                AudioRoute.Speaker -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                AudioRoute.Bluetooth -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                AudioRoute.Wired -> AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            audio.availableCommunicationDevices.firstOrNull { it.type == type }?.let { audio.setCommunicationDevice(it) }
                ?: audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = route == AudioRoute.Speaker
        }
    }

    actual fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        audio.mode = previousMode
    }
}
