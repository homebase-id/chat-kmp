package id.homebase.chat.services.calling

import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
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

private fun AVAudioSession.portTypes(ports: List<*>?): List<String?> =
    ports.orEmpty().map { (it as? AVAudioSessionPortDescription)?.portType }

internal fun AVAudioSession.currentAudioRoute(): AudioRoute? =
    portTypes(currentRoute.outputs).firstNotNullOfOrNull(::audioRouteOf)

internal fun AVAudioSession.availableAudioRoutes(): List<AudioRoute> {
    val connected = (portTypes(currentRoute.outputs) + portTypes(availableInputs)).mapNotNullTo(HashSet(), ::audioRouteOf)
    return listOf(AudioRoute.Earpiece, AudioRoute.Speaker) + listOf(AudioRoute.Bluetooth, AudioRoute.Wired).filter { it in connected }
}
