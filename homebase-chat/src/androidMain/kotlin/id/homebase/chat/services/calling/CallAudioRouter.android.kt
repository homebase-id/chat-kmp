package id.homebase.chat.services.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import id.homebase.api.ActivityProvider

internal val BluetoothDeviceTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
internal val WiredDeviceTypes = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

actual class CallAudioRouter actual constructor() {
    private val audio: AudioManager =
        ActivityProvider.requireApplicationContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var started = false
    private var current = AudioRoute.Earpiece

    actual fun start(video: Boolean): AudioRoute {
        if (started) return current
        started = true
        previousMode = audio.mode
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
            .also { audio.requestAudioFocus(it) }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val routes = platformAudioRoutes()
        val route = when {
            AudioRoute.Wired in routes -> AudioRoute.Wired
            AudioRoute.Bluetooth in routes -> AudioRoute.Bluetooth
            video -> AudioRoute.Speaker
            else -> AudioRoute.Earpiece
        }
        setRoute(route)
        return route
    }

    actual fun setRoute(route: AudioRoute) {
        current = route
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val types = when (route) {
                AudioRoute.Earpiece -> setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                AudioRoute.Speaker -> setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioRoute.Bluetooth -> BluetoothDeviceTypes
                AudioRoute.Wired -> WiredDeviceTypes
            }
            audio.availableCommunicationDevices.firstOrNull { it.type in types }?.let { audio.setCommunicationDevice(it) }
                ?: audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = route == AudioRoute.Speaker
        }
    }

    actual fun stop() {
        if (!started) return
        started = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        audio.mode = previousMode
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}
