package id.homebase.chat.services.calling

import com.shepeliev.webrtckmp.BundlePolicy
import com.shepeliev.webrtckmp.RtcpMuxPolicy
import id.homebase.api.client.calling.CallIceServer
import org.junit.Assert.assertEquals
import org.junit.Test

class WebRtcKmpConfigurationTest {
    @Test
    fun thePeerConnectionRequiresMaxBundleAndRtcpMux() {
        val config = callRtcConfiguration(listOf(CallIceServer(urls = listOf("stun:stun.example.org:3478"))))

        assertEquals(BundlePolicy.MaxBundle, config.bundlePolicy)
        assertEquals(RtcpMuxPolicy.Require, config.rtcpMuxPolicy)
        assertEquals(listOf("stun:stun.example.org:3478"), config.iceServers.single().urls)
    }
}
