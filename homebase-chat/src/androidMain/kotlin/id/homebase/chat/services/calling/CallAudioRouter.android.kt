package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import id.homebase.api.ActivityProvider
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.concurrent.Volatile

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
    @Volatile private var current = AudioRoute.Earpiece
    private var stopFollowingDevices: (() -> Unit)? = null
    private var stopFollowingMode: (() -> Unit)? = null

    actual fun start(video: Boolean, onRouteChanged: (AudioRoute) -> Unit): AudioRoute {
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
        var known = availableAudioRoutes()
        val route = preferredRoute(known, previous = emptyList(), current = null, video = video)!!
        setRoute(route)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) followMode()
        stopFollowingDevices = onAudioDevicesChanged {
            val routes = availableAudioRoutes()
            val next = preferredRoute(routes, previous = known, current = current, video = video)
            known = routes
            if (next != null && next != current) {
                setRoute(next)
                onRouteChanged(next)
            }
        }
        return route
    }

    // Answering while the ringtone still holds MODE_RINGTONE drops the device set then (Pixel, API 37), so set it again in communication mode.
    @RequiresApi(Build.VERSION_CODES.S)
    private fun followMode() {
        val executor = ActivityProvider.requireApplicationContext().mainExecutor
        val modeListener = AudioManager.OnModeChangedListener { mode ->
            if (started && mode == AudioManager.MODE_IN_COMMUNICATION) setRoute(current)
        }
        val deviceListener = AudioManager.OnCommunicationDeviceChangedListener {
            Logger.i(TAG) { "communication device now ${it?.type} wanted $current" }
        }
        audio.addOnModeChangedListener(executor, modeListener)
        audio.addOnCommunicationDeviceChangedListener(executor, deviceListener)
        stopFollowingMode = {
            audio.removeOnModeChangedListener(modeListener)
            audio.removeOnCommunicationDeviceChangedListener(deviceListener)
        }
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
            val available = audio.availableCommunicationDevices
            val device = available.firstOrNull { it.type in types }
            val accepted = device?.let { audio.setCommunicationDevice(it) }
            if (device == null) audio.clearCommunicationDevice()
            Logger.i(tag = TAG) {
                "route set $route device=${device?.type} accepted=$accepted active=${audio.communicationDevice?.type} " +
                    "available=${available.map { it.type }} mode=${audio.mode}"
            }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = route == AudioRoute.Speaker
            @Suppress("DEPRECATION")
            Logger.i(TAG) { "route set $route speakerphoneOn=${audio.isSpeakerphoneOn} mode=${audio.mode}" }
        }
    }

    private companion object {
        const val TAG = "CallAudio"
    }

    actual fun stop() {
        if (!started) return
        started = false
        stopFollowingDevices?.invoke()
        stopFollowingDevices = null
        stopFollowingMode?.invoke()
        stopFollowingMode = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        audio.mode = previousMode
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}
