package id.homebase.chat.services.calling

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortBuiltInMic
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortHeadsetMic
import platform.AVFAudio.AVAudioSessionPortUSBAudio
import platform.AVFAudio.availableInputs
import platform.AVFAudio.currentRoute

internal fun audioRouteOf(portType: String?): AudioRoute? = when (portType) {
    AVAudioSessionPortBuiltInReceiver -> AudioRoute.Earpiece
    AVAudioSessionPortBuiltInSpeaker -> AudioRoute.Speaker
    AVAudioSessionPortBluetoothA2DP, AVAudioSessionPortBluetoothHFP, AVAudioSessionPortBluetoothLE -> AudioRoute.Bluetooth
    AVAudioSessionPortHeadphones, AVAudioSessionPortHeadsetMic, AVAudioSessionPortUSBAudio -> AudioRoute.Wired
    else -> null
}

private fun portTypes(ports: List<*>?): List<String?> =
    ports.orEmpty().map { (it as? AVAudioSessionPortDescription)?.portType }

internal fun AVAudioSession.currentAudioRoute(): AudioRoute? =
    portTypes(currentRoute.outputs).firstNotNullOfOrNull(::audioRouteOf)

internal fun AVAudioSession.availableAudioRoutes(): List<AudioRoute> =
    offeredRoutes((portTypes(currentRoute.outputs) + portTypes(availableInputs)).mapNotNullTo(HashSet(), ::audioRouteOf))

// Nil clears the preference, handing the input back to the system; the output override alone cannot leave a headset.
@OptIn(ExperimentalForeignApi::class)
internal fun AVAudioSession.preferInputFor(route: AudioRoute) {
    val port = availableInputs.orEmpty().filterIsInstance<AVAudioSessionPortDescription>().firstOrNull {
        when (route) {
            AudioRoute.Earpiece -> it.portType == AVAudioSessionPortBuiltInMic
            AudioRoute.Speaker -> false
            AudioRoute.Bluetooth, AudioRoute.Wired -> audioRouteOf(it.portType) == route
        }
    }
    setPreferredInput(port, error = null)
}
