package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallNotificationActions
import id.homebase.core.notifications.CallNotifier
import id.homebase.core.notifications.CallPushRouter
import id.homebase.core.notifications.RichNotificationData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopCallRingSinkTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private class FakeNotifier : CallNotifier {
        val shown = mutableListOf<Triple<String, String, String>>()
        val live = mutableSetOf<String>()
        val retracted = mutableListOf<String>()
        var actions: CallNotificationActions? = null

        override fun showIncoming(callId: String, title: String, body: String, actions: CallNotificationActions) {
            shown += Triple(callId, title, body)
            live += callId
            this.actions = actions
        }

        override fun retract(callId: String) {
            live -= callId
            retracted += callId
        }
    }

    private class FakeTone : RingTonePlayer {
        var starts = 0
        var stops = 0
        var playing = false
        override fun start() { starts++; playing = true }
        override fun stop() { stops++; playing = false }
    }

    private class Rig(scope: TestScope, selfId: String, backgrounded: Boolean = false) {
        val notifier = FakeNotifier()
        val tone = FakeTone()
        val missed = mutableListOf<RichNotificationData>()
        var callWindowFocused = 0
        val controller = CallController(
            selfOdinId = selfId,
            signaling = LoopbackSignaling(now = { scope.testScheduler.currentTime }).endpoint(selfId),
            engineFactory = CallEngineFactory { _ ->
                FakeCallEngine(scope.backgroundScope, FakeCallScript(), peerLabel = "peer", selfLabel = selfId)
            },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        lateinit var coordinator: CallRingCoordinator
        val sink = DesktopCallRingSink(
            scope = scope.backgroundScope,
            notifier = notifier,
            focusCallWindow = { callWindowFocused++ },
            tone = tone,
            incomingCallText = { "Incoming call" },
            acceptLabel = { "Accept" },
            declineLabel = { "Decline" },
            onAccept = { coordinator.accept(it) },
            onDecline = { coordinator.decline(it) },
            appInBackground = { backgrounded },
            missedNotification = { peer, label -> notification(peer, label) },
            showMissed = { missed += it },
        )

        init {
            coordinator = CallRingCoordinator(
                target = object : CallPushTarget {
                    override fun onPushRing(callId: String, from: String) = controller.onPushRing(callId, from)
                    override fun onPushHangup(callId: String) = controller.onPushHangup(callId)
                    override fun accept() = controller.accept()
                    override fun decline() = controller.decline()
                },
                state = controller.state,
                sink = sink,
                labelFor = { "Sam" },
                scope = scope.backgroundScope,
            ).also { it.start(); CallPushRouter.handler = it }
        }

        private fun notification(peer: String, label: String) = RichNotificationData(
            notificationId = 7, channelId = "messages", conversationId = "convo", title = "Missed call from $label", body = "",
            senderName = label, senderId = peer, senderImageBytes = null, timestamp = 0, payloadData = emptyMap(),
        )
    }

    @AfterTest
    fun clearHandler() {
        CallPushRouter.handler = null
    }

    private fun payload(typeId: String, callId: String) = mapOf(
        "data" to """{"senderId":"$sam","timestamp":1,"options":{"appId":"app","typeId":"$typeId","tagId":"$callId"}}""",
    )

    private fun ring(callId: String) = CallPushRouter.tryHandle(payload(CallPushRouter.RING_TYPE_ID, callId))
    private fun hangup(callId: String) = CallPushRouter.tryHandle(payload(CallPushRouter.HANGUP_TYPE_ID, callId))

    @Test
    fun ringingFocusesTheCallWindowStartsTheToneOnceAndStopRetractsBoth() = runTest {
        val tone = FakeTone()
        val notifier = FakeNotifier()
        var focused = 0
        val sink = DesktopCallRingSink(
            scope = backgroundScope, notifier = notifier, focusCallWindow = { focused++ }, tone = tone,
            incomingCallText = { "Incoming call" }, acceptLabel = { "Accept" }, declineLabel = { "Decline" },
            onAccept = {}, onDecline = {},
        )

        sink.ring("c1", "sam.demo.rocks")
        runCurrent()
        assertEquals(1, focused)
        assertEquals(1, tone.starts)
        assertEquals(listOf(Triple("c1", "sam.demo.rocks", "Incoming call")), notifier.shown)

        sink.ring("c1", "Samwise")
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(1, focused)
        assertEquals(1, tone.starts)
        assertEquals("Samwise", notifier.shown.last().second)

        sink.stopRinging("c1")
        assertFalse(tone.playing)
        assertEquals(listOf("c1"), notifier.retracted)
        assertTrue(notifier.live.isEmpty())

        sink.stopRinging("c1")
        sink.ended("c1")
        assertEquals(1, tone.stops)
        assertEquals(listOf("c1"), notifier.retracted)
    }

    @Test
    fun aRingPushFollowedByAHangupPushLeavesNoToneNoRingWindowAndNoNotification() = runTest {
        val rig = Rig(this, frodo)
        assertTrue(ring("c1"))
        runCurrent()
        assertTrue(rig.tone.playing)
        assertEquals(setOf("c1"), rig.notifier.live)
        assertEquals(1, rig.callWindowFocused)

        assertTrue(hangup("c1"))
        runCurrent()
        advanceTimeBy(5_000)
        assertFalse(rig.tone.playing)
        assertTrue(rig.notifier.live.isEmpty())
        assertFalse(rig.controller.state.value.isRingingFor("c1"))
    }

    @Test
    fun theNotificationAcceptButtonAnswersTheCallAndSilencesTheRing() = runTest {
        val rig = Rig(this, frodo)
        ring("c1")
        runCurrent()
        rig.notifier.actions!!.onAccept()
        runCurrent()
        val state = rig.controller.state.value
        assertFalse(state.isRingingFor("c1"))
        assertTrue(state.acceptPending || state.phase == CallPhase.Connecting || state.phase == CallPhase.Connected, "$state")
        assertFalse(rig.tone.playing)
        assertTrue(rig.notifier.live.isEmpty())
    }

    @Test
    fun theNotificationDeclineButtonDeclinesTheCall() = runTest {
        val rig = Rig(this, frodo)
        ring("c1")
        runCurrent()
        rig.notifier.actions!!.onDecline()
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Declined), rig.controller.state.value.phase)
        assertFalse(rig.tone.playing)
        assertTrue(rig.notifier.live.isEmpty())
    }

    @Test
    fun aStaleNotificationButtonDoesNothingOnceTheRingIsOver() = runTest {
        val rig = Rig(this, frodo)
        ring("c1")
        runCurrent()
        val actions = rig.notifier.actions!!
        hangup("c1")
        runCurrent()
        actions.onAccept()
        runCurrent()
        assertFalse(rig.controller.state.value.phase == CallPhase.Connecting)
    }

    @Test
    fun aRingThatExpiresWhileTheAppIsInTheBackgroundPostsOneMissedCallNotification() = runTest {
        val rig = Rig(this, frodo, backgrounded = true)
        ring("c1")
        runCurrent()
        advanceTimeBy(RING_TIMEOUT_MS + 1_000)
        runCurrent()
        assertEquals(listOf("Missed call from Sam"), rig.missed.map { it.title })
        assertTrue(rig.notifier.live.isEmpty())
    }

    @Test
    fun aMissedRingWithTheAppInTheForegroundPostsNoNotification() = runTest {
        val rig = Rig(this, frodo, backgrounded = false)
        ring("c1")
        runCurrent()
        advanceTimeBy(RING_TIMEOUT_MS + 1_000)
        runCurrent()
        assertTrue(rig.missed.isEmpty())
    }

    @Test
    fun aDeclinedRingIsNotAMissedCall() = runTest {
        val rig = Rig(this, frodo, backgrounded = true)
        ring("c1")
        runCurrent()
        rig.notifier.actions!!.onDecline()
        runCurrent()
        assertTrue(rig.missed.isEmpty())
    }
}
