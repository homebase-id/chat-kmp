package id.homebase.chat.services.calling

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionPortOverrideNone
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.AVFAudio.setActive

@OptIn(ExperimentalForeignApi::class)
actual class CallAudioRouter actual constructor() {
    private val session = AVAudioSession.sharedInstance()

    actual fun start() {
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, mode = AVAudioSessionModeVoiceChat, options = AVAudioSessionCategoryOptionAllowBluetooth, error = null)
        session.setActive(true, error = null)
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
