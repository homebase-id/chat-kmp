package id.homebase.chat.services.calling

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class WebRtcJavaCallEngineLockTest {

    // The stats timer holds the engine lock while getStats waits on the signaling thread; onTrack runs on that
    // thread inside setRemoteDescription, so if onTrack also waits on the lock the two never return.
    @Test
    fun aRemoteVideoTrackIsAcceptedWhileTheEngineLockIsHeld() = runBlocking<Unit> {
        val factory = PeerConnectionFactory(HeadlessAudioDeviceModule())
        val source = CustomVideoSource()
        val offerer = factory.createPeerConnection(RTCConfiguration(), object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) = Unit
        })
        val engine = WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule())
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "stats-timer-stand-in") {
            synchronized(engine.lock) {
                held.countDown()
                release.await()
            }
        }
        try {
            offerer.addTrack(factory.createVideoTrack("remote-video", source), listOf("remote"))
            val offer = suspendCancellableCoroutine { cont ->
                offerer.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) = cont.resume(description)
                    override fun onFailure(error: String) = error(error)
                })
            }
            suspendCancellableCoroutine { cont ->
                offerer.setLocalDescription(offer, object : SetSessionDescriptionObserver {
                    override fun onSuccess() = cont.resume(Unit)
                    override fun onFailure(error: String) = error(error)
                })
            }
            held.await()

            // Released inside the first 5 s stats tick, so a regression fails here instead of deadlocking close().
            // answerRestart is the one entry that goes straight to setRemoteDescription without taking the lock first.
            val answer = withTimeoutOrNull(2_000) { engine.answerRestart(offer.sdp) }
            release.countDown()

            assertNotNull(answer, "setRemoteDescription never completed: onTrack waited on the engine lock")
            assertNotNull(engine.remoteVideo.value)
        } finally {
            release.countDown()
            holder.join()
            engine.close()
            offerer.close()
            source.dispose()
            factory.dispose()
        }
    }
}
