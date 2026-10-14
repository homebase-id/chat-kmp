package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaStateLinkTest {
    private class End {
        val events = mutableListOf<MediaEvent>()
        val sent = mutableListOf<ByteArray>()
        val link = MediaStateLink("test") { events += it }
    }

    private fun pair(): Pair<End, End> {
        val a = End()
        val b = End()
        a.link.opened { a.sent += it; b.link.received(it); true }
        b.link.opened { b.sent += it; a.link.received(it); true }
        a.events.clear()
        b.events.clear()
        return a to b
    }

    @Test
    fun cameraAndMicChangesRoundTripToThePeer() {
        val (a, b) = pair()
        a.link.setCamera(true)
        a.link.setCamera(false)
        a.link.setMic(false)
        a.link.setCamera(true)
        assertEquals(
            listOf(
                MediaEvent.RemoteVideoChanged(true),
                MediaEvent.RemoteVideoChanged(false),
                MediaEvent.RemoteMuteChanged(true),
                MediaEvent.RemoteVideoChanged(true),
            ),
            b.events,
        )
    }

    @Test
    fun aCameraInterruptionTurnsVideoOffUntilItEndsWithoutTouchingTheUsersChoice() {
        val (a, b) = pair()
        a.link.setCamera(true)
        b.events.clear()
        a.link.cameraInterrupted(true, "background")
        a.link.cameraInterrupted(false, "reason=null")
        assertEquals(listOf<MediaEvent>(MediaEvent.RemoteVideoChanged(false), MediaEvent.RemoteVideoChanged(true)), b.events)

        b.events.clear()
        a.link.setCamera(false)
        a.link.cameraInterrupted(true, "background")
        a.link.cameraInterrupted(false, "reason=null")
        assertEquals(listOf<MediaEvent>(MediaEvent.RemoteVideoChanged(false)), b.events)
    }

    @Test
    fun aCapturerThatEndedStaysOff() {
        val (a, b) = pair()
        a.link.setCamera(true)
        a.link.capturerEnded()
        a.link.cameraInterrupted(false, "reason=null")
        assertEquals(listOf<MediaEvent>(MediaEvent.RemoteVideoChanged(true), MediaEvent.RemoteVideoChanged(false)), b.events)
    }

    @Test
    fun staleAndRepeatedStateIsDropped() {
        val (a, b) = pair()
        a.link.setCamera(true)
        val first = a.sent.last()
        a.link.setCamera(false)
        b.events.clear()
        b.link.received(first)
        a.link.resend()
        a.link.resend()
        assertEquals(emptyList(), b.events)
    }

    @Test
    fun stateSetBeforeTheChannelOpensIsSentOnOpen() {
        val a = End()
        val b = End()
        a.link.setCamera(true)
        a.link.setMic(false)
        a.link.opened { b.link.received(it); true }
        assertEquals(listOf(MediaEvent.RemoteVideoChanged(true), MediaEvent.RemoteMuteChanged(true)), b.events)
    }

    @Test
    fun unreadableOrExtendedMessagesNeverBreakTheReceiver() {
        val b = End()
        b.link.received("not json".encodeToByteArray())
        b.link.received("""{"type":"something-newer"}""".encodeToByteArray())
        assertTrue(b.events.isEmpty())
        b.link.received("""{"video":false,"audio":true,"seq":3,"screen":true}""".encodeToByteArray())
        assertEquals(listOf<MediaEvent>(MediaEvent.RemoteVideoChanged(false), MediaEvent.RemoteMuteChanged(false)), b.events)
    }

    @Test
    fun aRemoteTrackShowsVideoUnlessThePeerSaidItsCameraIsOff() {
        val b = End()
        b.link.remoteTrackArrived()
        assertEquals(listOf<MediaEvent>(MediaEvent.RemoteVideoChanged(true)), b.events)

        b.events.clear()
        b.link.received("""{"video":false,"audio":true,"seq":1}""".encodeToByteArray())
        b.link.remoteTrackArrived()
        assertEquals(listOf(MediaEvent.RemoteVideoChanged(false), MediaEvent.RemoteMuteChanged(false), MediaEvent.RemoteVideoChanged(false)), b.events)
    }

    @Test
    fun aCameraOffMessageThatLandsWhileTheTrackArrivalIsBeingSentIsNotOverwrittenByIt() {
        val events = mutableListOf<MediaEvent>()
        lateinit var link: MediaStateLink
        var armed = true
        link = MediaStateLink("test") { event ->
            if (armed && event == MediaEvent.RemoteVideoChanged(true)) {
                armed = false
                link.received("""{"video":false,"audio":true,"seq":1}""".encodeToByteArray())
            }
            events += event
        }

        link.remoteTrackArrived()

        assertEquals(MediaEvent.RemoteVideoChanged(false), events.filterIsInstance<MediaEvent.RemoteVideoChanged>().last())
    }

    @Test
    fun aRestoredCapturerTellsThePeerVideoIsBackWhileTheUsersCameraIsOn() {
        val (a, b) = pair()
        a.link.setCamera(true)
        a.link.capturerEnded()
        a.link.capturerRestored()
        assertEquals(
            listOf<MediaEvent>(MediaEvent.RemoteVideoChanged(true), MediaEvent.RemoteVideoChanged(false), MediaEvent.RemoteVideoChanged(true)),
            b.events,
        )

        b.events.clear()
        a.link.setCamera(false)
        a.link.capturerEnded()
        a.link.capturerRestored()
        assertEquals(listOf<MediaEvent>(MediaEvent.RemoteVideoChanged(false)), b.events)
    }
}
