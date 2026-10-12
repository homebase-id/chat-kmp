package id.homebase.chat.services.calling

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortOverrideNone
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.AVFAudio.currentRoute
import platform.AVFAudio.setActive

@OptIn(ExperimentalForeignApi::class)
actual class CallAudioRouter actual constructor() {
    private val session = AVAudioSession.sharedInstance()

    actual fun start(video: Boolean): AudioRoute {
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, mode = AVAudioSessionModeVoiceChat, options = AVAudioSessionCategoryOptionAllowBluetooth, error = null)
        session.setActive(true, error = null)
        val headset = session.currentRoute.outputs.any {
            val type = (it as? AVAudioSessionPortDescription)?.portType
            type == AVAudioSessionPortHeadphones || type == AVAudioSessionPortBluetoothHFP || type == AVAudioSessionPortBluetoothA2DP
        }
        val route = if (video && !headset) AudioRoute.Speaker else AudioRoute.Earpiece
        setRoute(route)
        return route
    }

    actual fun setRoute(route: AudioRoute) {
        session.overrideOutputAudioPort(
            if (route == AudioRoute.Speaker) AVAudioSessionPortOverrideSpeaker else AVAudioSessionPortOverrideNone,
            error = null,
        )
    }

    actual fun stop() {
        session.setActive(false, error = null)
    }
}
