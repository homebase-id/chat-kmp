package id.homebase.chat.services.calling

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

/** Real libwebrtc: a peer encodes H.264 at the level the other side's SDP advertises. */
class WebRtcJavaSendVideoTest {
    @Test
    fun theAnswerOurEngineSendsLetsThePeerEncodeTheCapAndTheSenderKeepsItsBitrateWindow() = runBlocking {
        val factory = PeerConnectionFactory(HeadlessAudioDeviceModule())
        val source = CustomVideoSource()
        val peer = factory.createPeerConnection(RTCConfiguration(), object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) = Unit
        })!!
        val engine = WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule())
        try {
            val track = factory.createVideoTrack("video0", source)
            val sender = peer.addTrack(track, listOf("homebase-call"))
            configureVideoSender(sender)

            val offer = peer.create()
            assertTrue(h264Levels(offer.sdp).isNotEmpty(), "libwebrtc offered no H.264")
            peer.set(local = true, offer)

            val answer = engine.answer(offer.sdp, video = false)
            peer.set(local = false, RTCSessionDescription(RTCSdpType.ANSWER, answer))

            val params = sender.parameters
            val h264 = params.codecs.filter { it.codecName == "H264" }
            assertTrue(h264.isNotEmpty(), "negotiated send codecs: ${params.codecs}")
            h264.forEach {
                val level = it.parameters.getValue("profile-level-id").takeLast(2).toInt(16)
                assertTrue(level >= H264_LEVEL_31, "send codec $it")
            }
            val encoding = params.encodings.single()
            assertEquals(SendVideoCap.MAX_BITRATE_BPS, encoding.maxBitrate)
            assertEquals(SendVideoCap.MIN_BITRATE_BPS, encoding.minBitrate)
        } finally {
            engine.close()
            peer.close()
            source.dispose()
            factory.dispose()
        }
    }

    private suspend fun RTCPeerConnection.create(): RTCSessionDescription = suspendCancellableCoroutine { cont ->
        createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) = cont.resume(description)
            override fun onFailure(error: String) = cont.resumeWithException(IllegalStateException(error))
        })
    }

    private suspend fun RTCPeerConnection.set(local: Boolean, description: RTCSessionDescription) =
        suspendCancellableCoroutine { cont ->
            val observer = object : SetSessionDescriptionObserver {
                override fun onSuccess() = cont.resume(Unit)
                override fun onFailure(error: String) = cont.resumeWithException(IllegalStateException(error))
            }
            if (local) setLocalDescription(description, observer) else setRemoteDescription(description, observer)
        }
}
