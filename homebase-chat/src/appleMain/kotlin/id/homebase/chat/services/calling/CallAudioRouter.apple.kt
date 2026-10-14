package id.homebase.chat.services.calling

import WebRTC.RTCAudioSession
import WebRTC.RTCAudioSessionConfiguration
import WebRTC.RTCAudioSessionDelegateProtocol
import WebRTC.setConfiguration
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeVideoChat
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortOverrideNone
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.AVFAudio.AVAudioSessionRouteChangeReason
import platform.AVFAudio.AVAudioSessionRouteDescription
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.currentRoute
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.concurrent.Volatile

// WebRTC activates the session itself (CallKit's didActivate, or its audio unit when no CallKit call owns audio) and then
// applies its global webRTCConfiguration, so the call's mode must go there; a mode set only on AVAudioSession is overwritten.
@OptIn(ExperimentalForeignApi::class)
actual class CallAudioRouter actual constructor() {
    private val session = AVAudioSession.sharedInstance()
    private val rtc = RTCAudioSession.sharedInstance()

    @Volatile
    private var route = AudioRoute.Earpiece

    private val events = object : NSObject(), RTCAudioSessionDelegateProtocol {
        // Category changes on activation drop a port override; the audio unit starts right after CallKit's didActivate.
        override fun audioSessionDidStartPlayOrRecord(session: RTCAudioSession) {
            overridePort(route)
            log("activated")
        }

        override fun audioSessionDidChangeRoute(
            session: RTCAudioSession,
            reason: AVAudioSessionRouteChangeReason,
            previousRoute: AVAudioSessionRouteDescription,
        ) = log("route changed reason=$reason")
    }

    actual fun start(video: Boolean, onRouteChanged: (AudioRoute) -> Unit): AudioRoute {
        val headset = session.currentRoute.outputs.any {
            val type = (it as? AVAudioSessionPortDescription)?.portType
            type == AVAudioSessionPortHeadphones || type == AVAudioSessionPortBluetoothHFP || type == AVAudioSessionPortBluetoothA2DP
        }
        val profile = callAudioProfile(video, headset)
        val config = RTCAudioSessionConfiguration().apply {
            category = AVAudioSessionCategoryPlayAndRecord!!
            mode = if (profile.mode == CallAudioMode.VideoChat) AVAudioSessionModeVideoChat!! else AVAudioSessionModeVoiceChat!!
            categoryOptions = AVAudioSessionCategoryOptionAllowBluetooth or
                (if (profile.defaultToSpeaker) AVAudioSessionCategoryOptionDefaultToSpeaker else 0u)
        }
        RTCAudioSessionConfiguration.setWebRTCConfiguration(config)
        rtc.addDelegate(events)
        val failed = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            rtc.lockForConfiguration()
            val ok = rtc.setConfiguration(config, error.ptr)
            rtc.unlockForConfiguration()
            if (ok) null else error.value?.localizedDescription ?: "unknown"
        }
        if (failed != null) Logger.w(TAG) { "audio session configure failed: $failed" }
        setRoute(profile.route)
        log("started video=$video")
        return profile.route
    }

    actual fun setRoute(route: AudioRoute) {
        this.route = route
        overridePort(route)
        log("route set $route")
    }

    private fun overridePort(route: AudioRoute) {
        session.overrideOutputAudioPort(
            if (route == AudioRoute.Speaker) AVAudioSessionPortOverrideSpeaker else AVAudioSessionPortOverrideNone,
            error = null,
        )
    }

    actual fun stop() {
        rtc.removeDelegate(events)
        if (!rtc.useManualAudio) {
            session.setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        }
    }

    private fun log(event: String) {
        val outputs = session.currentRoute.outputs.joinToString(",") { (it as? AVAudioSessionPortDescription)?.portType.toString() }
        Logger.i(TAG) { "audio session $event mode=${session.mode} category=${session.category} outputs=$outputs" }
    }

    private companion object {
        const val TAG = "CallAudio"
    }
}
