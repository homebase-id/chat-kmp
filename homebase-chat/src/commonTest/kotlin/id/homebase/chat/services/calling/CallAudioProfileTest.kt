package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals

class CallAudioProfileTest {
    @Test
    fun videoCallUsesVideoChatOnTheLoudspeaker() = assertEquals(
        CallAudioProfile(CallAudioMode.VideoChat, route = AudioRoute.Speaker),
        callAudioProfile(video = true, headset = false),
    )

    @Test
    fun videoCallWithHeadsetKeepsVideoChatButLeavesTheHeadsetRoute() = assertEquals(
        CallAudioProfile(CallAudioMode.VideoChat, route = AudioRoute.Earpiece),
        callAudioProfile(video = true, headset = true),
    )

    @Test
    fun voiceCallUsesVoiceChatOnTheEarpiece() = assertEquals(
        CallAudioProfile(CallAudioMode.VoiceChat, route = AudioRoute.Earpiece),
        callAudioProfile(video = false, headset = false),
    )
}
