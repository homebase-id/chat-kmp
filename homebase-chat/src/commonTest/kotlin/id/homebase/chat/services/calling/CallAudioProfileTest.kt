package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals

class CallAudioProfileTest {
    @Test
    fun videoCallUsesVideoChatOnTheLoudspeaker() = assertEquals(
        CallAudioProfile(CallAudioMode.VideoChat, defaultToSpeaker = true, route = AudioRoute.Speaker),
        callAudioProfile(video = true, headset = false),
    )

    @Test
    fun videoCallWithHeadsetKeepsVideoChatButLeavesTheHeadsetRoute() = assertEquals(
        CallAudioProfile(CallAudioMode.VideoChat, defaultToSpeaker = true, route = AudioRoute.Earpiece),
        callAudioProfile(video = true, headset = true),
    )

    @Test
    fun voiceCallUsesVoiceChatOnTheEarpiece() = assertEquals(
        CallAudioProfile(CallAudioMode.VoiceChat, defaultToSpeaker = false, route = AudioRoute.Earpiece),
        callAudioProfile(video = false, headset = false),
    )
}
