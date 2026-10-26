package id.homebase.agent

import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallController
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallEngineFactory
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.LoopbackSignaling
import id.homebase.chat.services.calling.WebRtcJavaCallEngine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class CallTurnLoopTest {
    private val owner = "samwise.gamgee.demo.rocks"

    private fun tonePcm(ms: Int, rate: Int = CALL_SAMPLE_RATE, amplitude: Double = 0.5): ByteArray {
        val samples = rate * ms / 1000
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2 * PI * 440.0 * i / rate) * amplitude * Short.MAX_VALUE).toInt()
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun shorts(pcm: ByteArray) = ShortArray(pcm.size / 2) { ((pcm[2 * it].toInt() and 0xff) or (pcm[2 * it + 1].toInt() shl 8)).toShort() }

    private class FakeAudio : CallAudioOut {
        val played = CopyOnWriteArrayList<Int>()
        val flushes = AtomicInteger()
        val cancelled = AtomicInteger()
        @Volatile var playMs = 0L

        override suspend fun play(pcm: ByteArray) {
            try {
                if (playMs > 0) delay(playMs)
                played += pcm.size
            } catch (e: CancellationException) {
                cancelled.incrementAndGet()
                throw e
            }
        }

        override fun flush() { flushes.incrementAndGet() }
    }

    private inner class Rig(
        val audio: FakeAudio = FakeAudio(),
        val maxMinutes: Int = 15,
        val brainTimeoutMs: Long = CALL_BRAIN_TIMEOUT_MS,
    ) {
        val frames = Channel<ShortArray>(Channel.UNLIMITED)
        val transcripts = ArrayDeque<String?>()
        val brainReplies = ArrayDeque<suspend () -> BrainOutcome>()
        val prompts = CopyOnWriteArrayList<String>()
        val spoken = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<OperatorCommand>()
        val notes = CopyOnWriteArrayList<String>()
        val hangups = AtomicInteger()
        var commandReply: String? = "🤖 no jobs"
        var speakResult: ByteArray? = ByteArray(960 * 50)

        fun say(text: String?) = transcripts.addLast(text)
        fun brain(text: String) = brainReplies.addLast { BrainOutcome.Output(text) }
        fun brainWith(block: suspend () -> BrainOutcome) = brainReplies.addLast(block)

        fun loop() = CallTurnLoop(
            peer = owner, nickname = "quagmire", frames = frames.receiveAsFlow(), audio = audio,
            transcribe = { _, _ -> transcripts.removeFirstOrNull() },
            brain = { prompt -> prompts += prompt; brainReplies.removeFirstOrNull()?.invoke() ?: BrainOutcome.Output("ok") },
            speak = { text -> spoken += text; speakResult },
            command = { commands += it; commandReply },
            postNotes = { notes += it },
            hangup = { hangups.incrementAndGet() },
            maxMinutes = maxMinutes, brainTimeoutMs = brainTimeoutMs, tailMs = 0,
        )

        fun silence(ms: Int) { repeat(ms / 10) { frames.trySend(ShortArray(480)) } }
        fun voice(ms: Int) { repeat(ms / 10) { frames.trySend(shorts(tonePcm(10))) } }
        fun utterance(ms: Int = 800) { silence(300); voice(ms); silence(900) }
    }

    private fun TestScope.start(rig: Rig, greeting: String? = null): Deferred<CallEnd> = async { rig.loop().run(greeting) }.also { runCurrent() }

    @Test
    fun aTranscriptLeadsToAReplyThatIsPlayed() = runTest {
        val rig = Rig()
        rig.say("what time is it"); rig.brain("It is noon.")
        val run = start(rig)
        rig.utterance()
        runCurrent()
        assertEquals(listOf("It is noon."), rig.spoken.filter { it != DEFAULT_CALL_FILLER })
        assertEquals(listOf(960 * 50), rig.audio.played)
        val prompt = rig.prompts.single()
        assertTrue("live voice call with $owner" in prompt && "at most two short spoken sentences" in prompt && "Say GOODBYE to end the call" in prompt)
        assertTrue("$owner: what time is it" in prompt)
        run.cancel()
    }

    @Test
    fun onlyTheLastSixTurnsReachTheBrain() = runTest {
        val rig = Rig()
        for (i in 1..4) { rig.say("question $i"); rig.brain("answer $i") }
        val run = start(rig)
        repeat(4) { rig.utterance(); runCurrent() }
        val last = rig.prompts.last()
        assertTrue("question 4" in last && "answer 3" in last && "question 2" in last)
        assertTrue("question 1" !in last && "answer 1" in last)
        run.cancel()
    }

    @Test
    fun goodbyeIsSpokenThenTheCallHangsUp() = runTest {
        val rig = Rig()
        rig.say("thanks that is all"); rig.brain("Bye now. GOODBYE")
        val run = start(rig)
        rig.utterance()
        runCurrent()
        assertEquals(CallEnd.GOODBYE, run.await())
        assertEquals(1, rig.hangups.get())
        assertEquals(listOf(960 * 50), rig.audio.played)
        assertTrue(rig.spoken.any { "GOODBYE" in it })
    }

    @Test
    fun anOperatorCommandIsRoutedAndNeverReachesTheBrain() = runTest {
        val rig = Rig()
        rig.say("Status.")
        val run = start(rig)
        rig.utterance()
        runCurrent()
        assertEquals(listOf<OperatorCommand>(OperatorCommand.Status), rig.commands.toList())
        assertTrue(rig.prompts.isEmpty())
        assertTrue("no jobs" in rig.spoken && rig.spoken.none { "🤖" in it })
        assertEquals(1, rig.audio.played.size)
        run.cancel()
    }

    @Test
    fun aDisabledCommandFallsThroughToTheBrain() = runTest {
        val rig = Rig().also { it.commandReply = null }
        rig.say("cancel"); rig.brain("Nothing to cancel.")
        val run = start(rig)
        rig.utterance()
        runCurrent()
        assertEquals(1, rig.commands.size)
        assertEquals(1, rig.prompts.size)
        run.cancel()
    }

    @Test
    fun spokenCommandsOnlyMatchWholeShortUtterances() {
        assertEquals(OperatorCommand.Status, spokenCommand("Status!", "quagmire"))
        assertEquals(OperatorCommand.New, spokenCommand("new", "quagmire"))
        assertEquals(OperatorCommand.Cancel(null), spokenCommand("Cancel.", "quagmire"))
        assertEquals(OperatorCommand.Cancel(3), spokenCommand("cancel 3", "quagmire"))
        assertNull(spokenCommand("what is the status of the build", "quagmire"))
        assertNull(spokenCommand("schedules", "quagmire"))
    }

    @Test
    fun whisperFillerIsIgnoredWithoutCountingAsAFailure() = runTest {
        val rig = Rig()
        repeat(4) { rig.say("[BLANK_AUDIO]") }
        rig.say("(silence)"); rig.say("  ")
        val run = start(rig)
        repeat(6) { rig.utterance(); runCurrent() }
        assertTrue(rig.prompts.isEmpty())
        assertTrue(run.isActive)
        assertNull(cleanTranscript("[ Silence ]"))
        assertEquals("hello there", cleanTranscript("[MUSIC] hello  there (laughs)"))
        run.cancel()
    }

    @Test
    fun aVoicedOnsetWhileTheBotIsSpeakingFlushesPlaybackAndCancelsTheReply() = runTest {
        val rig = Rig(FakeAudio().also { it.playMs = 10_000 })
        rig.say("tell me a story"); rig.brain("Once upon a time.")
        rig.say("stop, what is two plus two"); rig.brain("Four.")
        val run = start(rig)
        rig.utterance()
        advanceTimeBy(2_000)
        assertEquals(0, rig.audio.flushes.get())
        assertEquals(1, rig.prompts.size)
        rig.audio.playMs = 0
        rig.voice(800)
        rig.silence(900)
        runCurrent()
        assertEquals(1, rig.audio.flushes.get())
        assertEquals(1, rig.audio.cancelled.get())
        assertEquals(2, rig.prompts.size)
        assertEquals(listOf(960 * 50), rig.audio.played)
        run.cancel()
    }

    @Test
    fun threeFailuresInARowSayLosingYouAndHangUp() = runTest {
        val rig = Rig()
        repeat(3) { rig.say(null) }
        val run = start(rig)
        repeat(3) { rig.utterance(); runCurrent() }
        assertEquals(CallEnd.FAILURES, run.await())
        assertEquals(1, rig.hangups.get())
        assertTrue("I'm losing you." in rig.spoken)
        assertEquals(1, rig.audio.played.size)
    }

    @Test
    fun aSuccessfulTurnResetsTheFailureCount() = runTest {
        val rig = Rig()
        listOf("a", "b", "c", "d", "e", "f").forEach { rig.say(it) }
        listOf(false, false, true, false, false, false).forEach { ok -> rig.brainWith { if (ok) BrainOutcome.Output("fine") else BrainOutcome.Failed("boom") } }
        val run = start(rig)
        repeat(5) { rig.utterance(); runCurrent() }
        assertTrue(run.isActive)
        assertEquals(0, rig.hangups.get())
        rig.utterance(); runCurrent()
        assertEquals(CallEnd.FAILURES, run.await())
        assertEquals(1, rig.hangups.get())
    }

    @Test
    fun aBrainThatTakesOverTheTimeoutIsAFailure() = runTest {
        val rig = Rig(brainTimeoutMs = 60_000)
        repeat(3) { rig.say("hello"); rig.brainWith { delay(1_000_000); BrainOutcome.Output("late") } }
        val run = start(rig)
        repeat(3) { rig.utterance(); runCurrent(); advanceTimeBy(60_001) }
        assertEquals(CallEnd.FAILURES, run.await())
        assertTrue("late" !in rig.spoken)
    }

    @Test
    fun callMaxMinutesEndsTheCallUnderVirtualTime() = runTest {
        val rig = Rig(maxMinutes = 2)
        val run = start(rig)
        advanceTimeBy(119_000)
        runCurrent()
        assertTrue(run.isActive)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(CallEnd.MAX_MINUTES, run.await())
        assertEquals(1, rig.hangups.get())
    }

    @Test
    fun theFillerIsPlayedOnceWhileASlowReplyIsMade() = runTest {
        val rig = Rig()
        rig.say("hard question"); rig.brainWith { delay(4_000); BrainOutcome.Output("Here you go.") }
        val run = start(rig)
        runCurrent()
        rig.utterance()
        runCurrent()
        assertTrue(rig.audio.played.isEmpty())
        advanceTimeBy(CALL_FILLER_AFTER_MS + 100)
        assertEquals(1, rig.audio.played.size)
        advanceTimeBy(4_000)
        assertEquals(2, rig.audio.played.size)
        assertEquals(1, rig.spoken.count { it == DEFAULT_CALL_FILLER })
        run.cancel()
    }

    @Test
    fun aQuickReplyNeverPlaysTheFiller() = runTest {
        val rig = Rig()
        rig.say("hi"); rig.brain("Hello.")
        val run = start(rig)
        rig.utterance()
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(1, rig.audio.played.size)
        run.cancel()
    }

    @Test
    fun theGreetingIsSpokenBeforeAnyoneTalks() = runTest {
        val rig = Rig()
        val run = start(rig, "Quagmire here.")
        runCurrent()
        assertEquals("Quagmire here.", rig.spoken.first())
        assertEquals(1, rig.audio.played.size)
        run.cancel()
    }

    @Test
    fun theSummaryIsPostedToTheOwnerConversation() = runTest {
        val rig = Rig()
        rig.say("deploy the thing tomorrow"); rig.brain("Okay, tomorrow.")
        rig.brain("Owner asked to deploy tomorrow; agreed.")
        val loop = rig.loop()
        val run = async { loop.run() }
        rig.utterance()
        runCurrent()
        run.cancel()
        loop.postSummary()
        assertEquals(listOf("call notes: Owner asked to deploy tomorrow; agreed."), rig.notes.toList())
        assertTrue("deploy the thing tomorrow" in rig.prompts.last() && "Summarize" in rig.prompts.last())
    }

    @Test
    fun aFailedSummaryFallsBackToTheLastThingTheOwnerSaidAndAnEmptyCallPostsNothing() = runTest {
        val rig = Rig()
        rig.say("remind me about milk"); rig.brain("Sure.")
        rig.brainWith { BrainOutcome.Failed("down") }
        val loop = rig.loop()
        val run = async { loop.run() }
        rig.utterance()
        runCurrent()
        run.cancel()
        loop.postSummary()
        assertTrue(rig.notes.single().startsWith("call notes: ") && "remind me about milk" in rig.notes.single())

        val quiet = Rig()
        quiet.loop().postSummary()
        assertTrue(quiet.notes.isEmpty())
    }

    @Test
    fun theLoopEndsWhenTheRemoteAudioEnds() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.frames.close()
        runCurrent()
        assertEquals(CallEnd.REMOTE_ENDED, run.await())
        assertEquals(0, rig.hangups.get())
    }

    private class FakeCall : CallSession {
        val mutable = MutableStateFlow(CallState())
        override val state: StateFlow<CallState> = mutable.asStateFlow()
        override val transitions: Flow<CallState> = mutable
        override val videos: StateFlow<CallVideos> = MutableStateFlow(CallVideos())
        val accepts = AtomicInteger()
        val declines = AtomicInteger()
        val hangups = AtomicInteger()

        fun ring(peer: String, id: String = "call-1") {
            mutable.value = CallState(phase = CallPhase.Incoming, callId = id, peer = peer, direction = CallDirection.Incoming)
        }

        override fun startCall(peer: String, video: Boolean) = Unit
        override fun accept() { accepts.incrementAndGet(); mutable.value = mutable.value.copy(phase = CallPhase.Connected) }
        override fun decline() { declines.incrementAndGet(); mutable.value = mutable.value.copy(phase = CallPhase.Ended(CallEndReason.Declined)) }
        override fun hangup() { hangups.incrementAndGet(); mutable.value = mutable.value.copy(phase = CallPhase.Ended(CallEndReason.Hangup(remote = false))) }
        override fun setMuted(muted: Boolean) = Unit
        override fun setVideo(on: Boolean) = Unit
        override fun switchCamera() = Unit
        override fun setAudioRoute(route: AudioRoute) = Unit
    }

    private fun inbound(call: FakeCall, converse: suspend (OdinId) -> Unit = {}, enabled: Boolean = true, ready: Boolean = true, single: Mutex = Mutex()) =
        InboundCalls(setOf(OdinId(owner)), enabled, call, single, converse, { ready }, acceptDelayMs = 1_000, connectTimeoutMs = 5_000)

    @Test
    fun anOperatorCallIsAnsweredAfterASecondAndTheLoopRuns() = runTest {
        val call = FakeCall()
        val conversed = CopyOnWriteArrayList<String>()
        call.ring(owner)
        val handled = async { inbound(call, { conversed += it.toString() }).handle(call.state.value) }
        runCurrent()
        assertEquals(0, call.accepts.get())
        advanceTimeBy(999); runCurrent()
        assertEquals(0, call.accepts.get())
        advanceTimeBy(2); runCurrent()
        handled.await()
        assertEquals(1, call.accepts.get())
        assertEquals(0, call.declines.get())
        assertEquals(listOf(owner), conversed.toList())
        assertEquals(1, call.hangups.get())
    }

    @Test
    fun aStrangerIsDeclinedImmediately() = runTest {
        val call = FakeCall()
        call.ring("stranger.example.org")
        inbound(call).handle(call.state.value)
        assertEquals(1, call.declines.get())
        assertEquals(0, call.accepts.get())
        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    fun aCallWhileAlreadyInACallIsDeclined() = runTest {
        val call = FakeCall()
        val busy = Mutex().also { it.tryLock() }
        call.ring(owner)
        inbound(call, single = busy).handle(call.state.value)
        assertEquals(1, call.declines.get())
        assertEquals(0, call.accepts.get())
    }

    @Test
    fun anOperatorIsDeclinedWhenCallsAreOffOrTheLoopIsNotWired() = runTest {
        val off = FakeCall().also { it.ring(owner) }
        inbound(off, enabled = false).handle(off.state.value)
        val unwired = FakeCall().also { it.ring(owner) }
        inbound(unwired, ready = false).handle(unwired.state.value)
        for (call in listOf(off, unwired)) {
            assertEquals(1, call.declines.get())
            assertEquals(0, call.accepts.get())
        }
    }

    @Test
    fun aCallerWhoHangsUpBeforeTheAnswerIsNotAnswered() = runTest {
        val call = FakeCall()
        call.ring(owner)
        val handled = async { inbound(call).handle(call.state.value) }
        runCurrent()
        call.mutable.value = call.mutable.value.copy(phase = CallPhase.Ended(CallEndReason.Cancelled))
        advanceTimeBy(2_000)
        handled.await()
        assertEquals(0, call.accepts.get())
        assertEquals(0, call.hangups.get())
    }

    @Test
    fun startHandlesEachRingingCallOnce() = runTest {
        val call = FakeCall()
        val conversed = AtomicInteger()
        val watcher = inbound(call, { conversed.incrementAndGet() }).start(this)
        runCurrent()
        call.ring(owner)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, conversed.get())
        call.ring(owner, "call-2")
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2, conversed.get())
        watcher.cancel()
    }

    private fun runWithin(timeoutMs: Long, body: () -> Unit) {
        var failure: Throwable? = null
        val worker = Thread({ try { body() } catch (e: Throwable) { failure = e } }, "native-test").apply { isDaemon = true }
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) throw AssertionError("test body still running after $timeoutMs ms:\n" + worker.stackTrace.joinToString("\n") { "  at $it" })
        failure?.let { throw it }
    }

    @Test
    fun theCalleeLoopHearsAToneAnswersAndTheCallerHearsTheAnswer() = runWithin(180_000) {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val callerPlayer = PcmPlayer()
            val calleePlayer = PcmPlayer()
            val callerRemote = RemoteAudio()
            val calleeRemote = RemoteAudio()
            try {
                val net = LoopbackSignaling()
                val callerFactory = CallEngineFactory { WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule(), audioSource = { callerPlayer.source }, remoteAudioSink = callerRemote, renderVideo = false) }
                val calleeFactory = CallEngineFactory { WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule(), audioSource = { calleePlayer.source }, remoteAudioSink = calleeRemote, renderVideo = false) }
                val caller = CallController("frodo.baggins.demo.rocks", net.endpoint("frodo.baggins.demo.rocks"), callerFactory, scope)
                val callee = CallController(owner, net.endpoint(owner), calleeFactory, scope)

                val heardMs = AtomicInteger(-1)
                val loop = CallTurnLoop(
                    peer = "frodo.baggins.demo.rocks", nickname = "quagmire", frames = calleeRemote.frames, audio = calleePlayer,
                    transcribe = { wav, _ ->
                        val ms = (wav.size - 44) / 2 / 16
                        heardMs.set(ms)
                        if (ms in 500..2_500) "hello" else ""
                    },
                    brain = { BrainOutcome.Output("hi GOODBYE") },
                    speak = { tonePcm(600) },
                    command = { null },
                    postNotes = { },
                    hangup = callee::hangup,
                    tailMs = 300,
                )
                val ended = async { loop.run() }

                caller.startCall(owner, video = false)
                withTimeout(15_000) { callee.state.first { it.phase == CallPhase.Incoming && it.pendingOfferSdp != null } }
                callee.accept()
                withTimeout(30_000) {
                    caller.state.first { it.phase == CallPhase.Connected }
                    callee.state.first { it.phase == CallPhase.Connected }
                }
                delay(1_500)

                val answer = async { withTimeout(30_000) { callerRemote.frames.first { rms(it) > 0.05 } } }
                callerPlayer.play(tonePcm(1_000))
                assertTrue(rms(answer.await()) > 0.05)
                assertEquals(CallEnd.GOODBYE, withTimeout(30_000) { ended.await() })
                assertTrue(heardMs.get() in 500..2_500, "utterance length ${heardMs.get()} ms")

                withTimeout(10_000) {
                    caller.state.first { it.phase is CallPhase.Ended }
                    callee.state.first { it.phase is CallPhase.Ended }
                }
                caller.close()
                callee.close()
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                callerPlayer.stop()
                calleePlayer.stop()
            }
        }
    }
}
