package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlin.concurrent.Volatile
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CallController(
    selfOdinId: String,
    private val signaling: CallSignaling,
    private val engineFactory: CallEngineFactory,
    private val scope: CoroutineScope,
    private val selfLabel: String = selfOdinId,
    private val stunUrls: () -> List<String> = { emptyList() },
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ringTimeoutMs: Long = RING_TIMEOUT_MS,
    private val offerRepublishMs: Long = OFFER_REPUBLISH_MS,
) : CallSession {
    private val log = Logger.withTag(TAG)
    private val machine = CallStateMachine(selfOdinId)
    private val _state = MutableStateFlow(CallState())
    override val state: StateFlow<CallState> = _state.asStateFlow()

    private val _videos = MutableStateFlow(CallVideos())
    override val videos: StateFlow<CallVideos> = _videos.asStateFlow()

    private val _mediaDevices = MutableStateFlow<CallMediaDevices?>(null)
    override val mediaDevices: StateFlow<CallMediaDevices?> = _mediaDevices.asStateFlow()

    // Everything below the inbox is touched only from the inbox loop; other threads just post.
    private sealed interface Command {
        class Event(val event: CallEvent, val processed: CompletableDeferred<CallState>? = null) : Command
        class RepublishOffer(val callId: String) : Command
        data object Close : Command
    }

    private val inbox = Channel<Command>(Channel.UNLIMITED)
    private var engine: CallEngine? = null
    private var engineEvents: Job? = null
    private var engineVideos: Job? = null
    private var remoteReady = false
    private val bufferedIce = mutableListOf<CallEffect.AddIce>()
    private var offerSdp: String? = null
    private var answerSdp: String? = null
    private val timers = mutableMapOf<CallTimer, Job>()
    private val heldIce = mutableListOf<LocalCandidate>()
    private var publishedIce = 0
    private var offerSeq = 0
    private var offerVideo = false
    private var publishedOfferSdp: String? = null
    private var republishJob: Job? = null
    private var restartOfferSdp: String? = null
    private val endedCalls = ArrayDeque<String>()
    @Volatile private var inFlight: Job? = null

    private val collectors: List<Job>
    private val inboxLoop: Job

    init {
        val signals = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaling.incoming.collect { post(CallEvent.Signal(it)) }
        }
        inboxLoop = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (command in inbox) {
                    when (command) {
                        is Command.Event -> try {
                            processInterruptibly(command.event)
                        } finally {
                            command.processed?.complete(_state.value)
                        }
                        is Command.RepublishOffer -> republishOffer(command.callId)
                        Command.Close -> break
                    }
                }
            } finally {
                withContext(NonCancellable) { teardown() }
            }
        }
        collectors = listOf(signals, inboxLoop)
    }

    fun close() {
        inbox.trySend(Command.Close)
    }

    /** Hangs up any call and releases camera and mic before returning; a hangup still unsent after [hangupBudgetMs] is dropped. */
    suspend fun endForQuit(hangupBudgetMs: Long = QUIT_HANGUP_BUDGET_MS) {
        val hungUp = CompletableDeferred<CallState>()
        if (enqueue(CallEvent.UserHangup, hungUp)) {
            withTimeoutOrNull(hangupBudgetMs) { hungUp.await() }
        }
        inboxLoop.cancelAndJoin()
    }

    private fun teardown() {
        collectors.forEach { it.cancel() }
        inbox.close()
        while (true) {
            val left = inbox.tryReceive().getOrNull() ?: break
            (left as? Command.Event)?.processed?.cancel()
        }
        cancelTimers()
        releaseEngine()
    }

    override fun startCall(peer: String, video: Boolean) = startCall(peer, video, Uuid.random().toString())

    fun startCall(peer: String, video: Boolean, callId: String) = post(CallEvent.UserCall(callId, peer, video))

    override fun accept() = post(CallEvent.UserAccept)
    override fun decline() {
        log.i(Throwable("decline() requested")) { "decline requested in ${_state.value.phase} call=${_state.value.callId}" }
        post(CallEvent.UserDecline)
    }

    override fun hangup() {
        log.i(Throwable("hangup() requested")) { "hangup requested in ${_state.value.phase} call=${_state.value.callId}" }
        post(CallEvent.UserHangup)
    }
    override fun setMuted(muted: Boolean) = post(CallEvent.SetMuted(muted))
    override fun setVideo(on: Boolean) = post(CallEvent.SetVideo(on))
    override fun switchCamera() = post(CallEvent.SwitchCamera)
    override fun setAudioRoute(route: AudioRoute) = post(CallEvent.SetRoute(route))
    fun onPermissionDenied() = post(CallEvent.EngineError(FailReason.Permission))

    fun onPushRing(callId: String, from: String) = post(CallEvent.PushRing(callId, from))
    fun pushRing(callId: String, from: String): Deferred<CallState> =
        CompletableDeferred<CallState>().also { inbox.trySend(Command.Event(CallEvent.PushRing(callId, from), it)) }

    fun onPushHangup(callId: String) = post(CallEvent.PushHangup(callId))

    private fun post(event: CallEvent) {
        enqueue(event)
    }

    private fun enqueue(event: CallEvent, processed: CompletableDeferred<CallState>? = null): Boolean {
        if (endsActiveCall(event)) inFlight?.cancel()
        return inbox.trySend(Command.Event(event, processed)).isSuccess
    }

    // An engine call can hang (a native deadlock, a stalled setRemoteDescription); ending the call must not wait behind it.
    private fun endsActiveCall(event: CallEvent): Boolean {
        val s = _state.value
        if (!s.isActive) return false
        return when (event) {
            CallEvent.UserHangup, CallEvent.UserDecline -> true
            is CallEvent.Signal -> {
                val sig = event.inbound.signal
                sig is CallSignal.Hangup && !sig.reason.isElsewhere && sig.callId == s.callId && event.inbound.from == s.peer
            }
            else -> false
        }
    }

    private suspend fun processInterruptibly(event: CallEvent) = coroutineScope {
        val job = launch(start = CoroutineStart.LAZY) { process(event) }
        inFlight = job
        try {
            job.join()
        } finally {
            inFlight = null
        }
    }

    private suspend fun send(peer: String, signal: CallSignal) {
        val outbound = CallChannel.outbound(peer, signal, selfLabel)
        log.i { "send ${describe(signal)} call=${signal.callId} to=$peer push=${outbound.push?.kind?.wire}" }
        signaling.send(outbound)
    }

    // While reconnecting the lost network is the expected cause; the next Reconnect tick resends and its timeout ends the call.
    private suspend fun sendUnlessOffline(peer: String, signal: CallSignal, state: CallState) {
        if (state.phase != CallPhase.Reconnecting) return send(peer, signal)
        try {
            send(peer, signal)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "${describe(signal)} call=${signal.callId} not sent while reconnecting; next tick retries" }
        }
    }

    // Telling the callee's other devices to stop ringing must never fail the call this device just took or declined.
    private suspend fun sendToSiblings(peer: String, signal: CallSignal, state: CallState) {
        try {
            send(peer, signal)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "${describe(signal)} call=${signal.callId} not delivered to sibling devices" }
        }
    }

    private fun describe(signal: CallSignal): String = when (signal) {
        is CallSignal.Hangup -> "hangup(${signal.reason})"
        is CallSignal.Offer -> "offer(seq=${signal.seq})"
        else -> signal::class.simpleName.orEmpty()
    }

    private suspend fun process(event: CallEvent) {
        if (isReplayOfEndedCall(event)) return
        val transition = machine.reduce(_state.value, event, clock())
        _state.value = transition.state
        if (transition.state.phase is CallPhase.Ended) transition.state.callId?.let(::rememberEnded)
        for (effect in transition.effects) run(effect, transition.state)
    }

    // The relay replays the last blob per sender on every socket reconnect, so an offer for a call that already ended comes back.
    private fun isReplayOfEndedCall(event: CallEvent): Boolean {
        val callId = when (event) {
            is CallEvent.Signal -> event.inbound.signal.callId
            is CallEvent.PushRing -> event.callId
            else -> return false
        }
        if (callId !in endedCalls) return false
        log.i { "ignored replay for ended call=$callId (${event::class.simpleName})" }
        return true
    }

    private fun rememberEnded(callId: String) {
        if (callId in endedCalls) return
        endedCalls.addLast(callId)
        if (endedCalls.size > ENDED_CALLS_REMEMBERED) endedCalls.removeFirst()
    }

    private fun engine(): CallEngine = engine ?: engineFactory.create(stunUrls()).also { created ->
        engine = created
        _mediaDevices.value = created.mediaDevices
        remoteReady = false
        engineEvents = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.events.collect { post(CallEvent.Media(it)) }
        }
        engineVideos = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(created.localVideo, created.remoteVideo, ::CallVideos).collect { _videos.value = it }
        }
    }

    private suspend fun run(effect: CallEffect, state: CallState) {
        try {
            when (effect) {
                is CallEffect.SendSignal ->
                    if ((effect.signal as? CallSignal.Hangup)?.reason?.isElsewhere == true) sendToSiblings(effect.peer, effect.signal, state)
                    else sendUnlessOffline(effect.peer, effect.signal, state)
                is CallEffect.CreateOffer -> {
                    val sdp = engine().createOffer(effect.video)
                    offerSdp = sdp
                    offerVideo = effect.video
                    offerSeq = 0
                    publishedOfferSdp = sdp
                    send(state.peer!!, CallSignal.Offer(state.callId!!, sdp, effect.video))
                }
                is CallEffect.HoldIce -> {
                    heldIce += LocalCandidate(effect.candidate, effect.sdpMid, effect.sdpMLineIndex)
                    scheduleOfferRepublish(state.callId!!)
                }
                is CallEffect.CreateAnswer -> {
                    val sdp = engine().answer(effect.offerSdp, effect.video)
                    offerSdp = effect.offerSdp
                    answerSdp = sdp
                    send(state.peer!!, CallSignal.Answer(state.callId!!, sdp))
                    markRemoteReady()
                    announceVerification(state.callId!!)
                }
                is CallEffect.ApplyRemoteAnswer -> {
                    engine().applyRemote(effect.sdp)
                    restartOfferSdp = null
                    markRemoteReady()
                    flushHeldIce(state)
                    if (answerSdp == null) {
                        answerSdp = effect.sdp
                        announceVerification(state.callId!!)
                    }
                }
                CallEffect.RestartIce -> {
                    // Resend the unanswered offer: a fresh one would orphan an answer already in flight for it.
                    val sdp = restartOfferSdp ?: engine().restartIce().also { restartOfferSdp = it }
                    sendUnlessOffline(state.peer!!, CallSignal.Restart(state.callId!!, sdp), state)
                }
                is CallEffect.AnswerRestart -> {
                    val sdp = engine().answerRestart(effect.offerSdp)
                    sendUnlessOffline(state.peer!!, CallSignal.RestartAnswer(state.callId!!, sdp), state)
                }
                is CallEffect.AddIce ->
                    if (remoteReady) engine?.addIce(effect.candidate, effect.sdpMid, effect.sdpMLineIndex)
                    else bufferedIce += effect
                is CallEffect.SetMuted -> engine?.setMuted(effect.muted)
                is CallEffect.SetVideo -> engine?.setVideo(effect.on)
                CallEffect.SwitchCamera -> engine?.switchCamera()
                is CallEffect.SetRoute -> engine?.setAudioRoute(effect.route)
                is CallEffect.StartTimer -> startTimer(effect.timer, effect.callId)
                CallEffect.CancelTimers -> cancelTimers()
                CallEffect.ReleaseEngine -> releaseEngine()
            }
        } catch (e: CallEngineException) {
            Logger.w(tag = TAG) { "engine failed: ${e.reason} ${e.message}" }
            post(CallEvent.EngineError(e.reason))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "call effect $effect failed" }
            post(CallEvent.EngineError(FailReason.Other))
        }
    }

    // Live Relay keeps only the last blob per sender, so while the call is unanswered every blob must be a complete offer.
    private fun scheduleOfferRepublish(callId: String) {
        if (republishJob?.isActive == true) return
        republishJob = scope.launch {
            delay(offerRepublishMs)
            inbox.trySend(Command.RepublishOffer(callId))
        }
    }

    private suspend fun republishOffer(callId: String) {
        republishJob = null
        val s = _state.value
        val base = offerSdp
        if (s.phase != CallPhase.Outgoing || s.callId != callId || base == null || heldIce.size == publishedIce) return
        val held = heldIce.toList()
        publishedIce = held.size
        val sdp = SdpCandidates.withCandidates(base, held)
        if (sdp == publishedOfferSdp) return
        publishedOfferSdp = sdp
        offerSeq++
        try {
            log.i { "republishing offer call=$callId seq=$offerSeq candidates=${held.size}" }
            send(s.peer!!, CallSignal.Offer(callId, sdp, offerVideo, offerSeq))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "offer republish call=$callId seq=$offerSeq failed" }
        }
        if (heldIce.size > publishedIce) scheduleOfferRepublish(callId)
    }

    private suspend fun flushHeldIce(state: CallState) {
        republishJob?.cancel()
        republishJob = null
        val pending = heldIce.toList()
        heldIce.clear()
        publishedIce = 0
        if (pending.isEmpty()) return
        log.i { "answer selected a device: trickling ${pending.size} held candidates call=${state.callId}" }
        for (c in pending) send(state.peer!!, CallSignal.Ice(state.callId!!, c.candidate, c.sdpMid, c.sdpMLineIndex))
    }

    private fun announceVerification(callId: String) {
        val code = CallVerification.code(offerSdp ?: return, answerSdp ?: return) ?: return
        post(CallEvent.Verification(callId, code))
    }

    private suspend fun markRemoteReady() {
        remoteReady = true
        val pending = bufferedIce.toList()
        bufferedIce.clear()
        pending.forEach { engine?.addIce(it.candidate, it.sdpMid, it.sdpMLineIndex) }
    }

    private fun startTimer(timer: CallTimer, callId: String) {
        timers.remove(timer)?.cancel()
        log.d { "timer $timer started call=$callId" }
        timers[timer] = scope.launch {
            when (timer) {
                CallTimer.Ring -> delay(ringTimeoutMs)
                CallTimer.Connect -> delay(CONNECT_TIMEOUT_MS)
                CallTimer.Reconnect -> {
                    while (true) {
                        delay(RECONNECT_TICK_MS)
                        post(CallEvent.TimerFired(timer, callId))
                    }
                }
            }
            post(CallEvent.TimerFired(timer, callId))
        }
    }

    private fun cancelTimers() {
        timers.values.forEach { it.cancel() }
        timers.clear()
        republishJob?.cancel()
        republishJob = null
    }

    private fun releaseEngine() {
        engineEvents?.cancel()
        engineEvents = null
        engineVideos?.cancel()
        engineVideos = null
        _videos.value = CallVideos()
        _mediaDevices.value = null
        engine?.close()
        engine = null
        remoteReady = false
        bufferedIce.clear()
        heldIce.clear()
        publishedIce = 0
        offerSeq = 0
        publishedOfferSdp = null
        offerSdp = null
        answerSdp = null
        restartOfferSdp = null
    }

    private companion object {
        const val TAG = "CallController"
        const val ENDED_CALLS_REMEMBERED = 32
        const val QUIT_HANGUP_BUDGET_MS = 2_000L
    }
}
