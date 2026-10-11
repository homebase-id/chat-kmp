package id.homebase.chat.calling

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.RTCDataChannelInit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRtcJavaNativesTest {
    @Test
    fun backendIsWebRtcJava() = assertEquals(WebRtcBackend.WebRtcJava, webRtcBackend)

    @Test
    fun realNativesCreateAnOffer() {
        val factory = PeerConnectionFactory()
        val observer = object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) = Unit
        }
        val pc = factory.createPeerConnection(RTCConfiguration(), observer)
        pc.createDataChannel("probe", RTCDataChannelInit())
        val latch = CountDownLatch(1)
        var sdp: String? = null
        pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                sdp = description.sdp
                latch.countDown()
            }
            override fun onFailure(error: String) = latch.countDown()
        })
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(sdp.orEmpty().startsWith("v=0"))
        pc.close()
        factory.dispose()
    }
}
