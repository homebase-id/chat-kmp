package id.homebase.chat.services.calling

import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSdpType
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

class GuardedWebRtcCallbacksTest {

    @Test
    fun aMapperThatThrowsNeverEscapesTheConnectionCallback() {
        val errors = mutableListOf<Throwable>()
        val observer = GuardedPeerObserver(
            mapper = PeerEventMapper { error("boom") },
            isClosed = { false },
            setLive = {},
            track = {},
            onError = { errors += it },
        )
        observer.onConnectionChange(RTCPeerConnectionState.CONNECTED)
        assertEquals(1, errors.size)
    }

    @Test
    fun aSecondResumeNeverEscapesTheSdpCallback() = runBlocking<Unit> {
        val errors = mutableListOf<Throwable>()
        val description = RTCSessionDescription(RTCSdpType.OFFER, "v=0")
        suspendCancellableCoroutine { cont ->
            val observer = GuardedCreateObserver(cont, { errors += it })
            observer.onSuccess(description)
            observer.onSuccess(description)
            observer.onFailure("late")
        }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun anErrorReachingTheErrorSinkIsItselfContained() {
        guardNative("x", { error("sink broke") }) { error("boom") }
    }
}
