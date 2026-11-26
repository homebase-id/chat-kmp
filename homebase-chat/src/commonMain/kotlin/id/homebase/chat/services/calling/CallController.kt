package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import id.homebase.core.util.Platform
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlin.concurrent.Volatile
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class CallController(
    selfOdinId: String,
    private val signaling: CallSignaling,
    private val engineFactory: CallEngineFactory,
    private val scope: CoroutineScope,
    private val iceServers: suspend (peer: String) -> CallIceServers = { CallIceServers.None },
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ringTimeoutMs: Long = RING_TIMEOUT_MS,
    private val offerRepublishMs: Long = OFFER_REPUBLISH_MS,
    private val deviceId: String = Uuid.random().toString(),
    private val networkChanges: Flow<Unit> = emptyFlow(),
) : CallSession {
    private val log = Logger.withTag(TAG)
    private val machine = CallStateMachine(selfOdinId, deviceId)
    private val _state = MutableStateFlow(CallState())
    override val state: StateFlow<CallState> = _state.asStateFlow()

    private val _transitions = MutableSharedFlow<CallState>(replay = 1, extraBufferCapacity = Channel.UNLIMITED)
        .apply { tryEmit(CallState()) }
    override val transitions: Flow<CallState> = _transitions.asSharedFlow()

    private val _videos = MutableStateFlow(CallVideos())
    override val videos: StateFlow<CallVideos> = _videos.asStateFlow()

    private val _mediaDevices = MutableStateFlow<CallMediaDevices?>(null)
    override val mediaDevices: StateFlow<CallMediaDevices?> = _mediaDevices.asStateFlow()

    // Everything below the inbox is touched only from the inbox loop; other threads just post.
    private sealed interface Command {
        class Event(val event: CallEvent, val processed: CompletableDeferred<CallState>? = null) : Command {
            val posted = TimeSource.Monotonic.markNow()
        }
        class RepublishOffer(val callId: String) : Command
        class RenewIce(val engine: CallEngine, val lease: IceLease) : Command
        data object Close : Command
    }

    private val inbox = Channel<Command>(Channel.UNLIMITED)
    private var engine: CallEngine? = null
    private var closingEngine: Job? = null
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
    private val heldIceInOffer = mutableMapOf<Int, Set<String>>()
    private var offerVideo = false
    private var publishedOfferSdp: String? = null
    private var republishJob: Job? = null
    private var iceSender: IceSender? = null
    private val remoteIce = mutableSetOf<CallEffect.AddIce>()
    private var answeredRestartOffer: String? = null
    private var restartOfferSdp: String? = null
    private var iceFetch: Pair<String, Deferred<IceLease>>? = null
    private var callIce: IceLease? = null
    private var renewJob: Job? = null
    // A request that fails must fail its own step, not the controller.
    private val iceScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
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
                            if (command.event == CallEvent.UserAccept) log.i { "accept waited ${command.posted.elapsedNow().inWholeMilliseconds}ms in the inbox" }
                            processInterruptibly(command.event)
                        } finally {
                            command.processed?.complete(_state.value)
                        }
                        is Command.RepublishOffer -> republishOffer(command.callId)
                        is Command.RenewIce -> renewIce(command.engine, command.lease)
                        Command.Close -> break
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    teardown()
                    closingEngine?.join()
                }
            }
        }
        // Watched only during a call, so desktop's interface polling never runs while idle.
        val network = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            _state.map { it.phase.isInCall }.distinctUntilChanged()
                .flatMapLatest { inCall -> if (inCall) networkChanges else emptyFlow() }
                .debounce(NETWORK_SETTLE_MS)
                .collect { post(CallEvent.NetworkChanged) }
        }
        collectors = listOf(signals, inboxLoop, network)
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
    override fun decline() = post(CallEvent.UserDecline)
    override fun hangup() = post(CallEvent.UserHangup)
    override fun setMuted(muted: Boolean) = post(CallEvent.SetMuted(muted))
    override fun setVideo(on: Boolean) = post(CallEvent.SetVideo(on))
    override fun switchCamera() = post(CallEvent.SwitchCamera)
    override fun setAudioRoute(route: AudioRoute) = post(CallEvent.SetRoute(route))
    fun onPermissionDenied() = post(CallEvent.EngineError(FailReason.Permission))

    /** Completes once the ring is processed; cancelled if this controller is closed first. */
    fun pushRing(callId: String, from: String, sentAtMs: Long = 0): Deferred<CallState> {
        val processed = CompletableDeferred<CallState>()
        if (!enqueue(CallEvent.PushRing(callId, from, sentAtMs), processed)) processed.cancel()
        return processed
    }

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
                sig is CallSignal.Hangup && !sig.reason.isElsewhere && sig.callId == s.callId && event.inbound.from == s.peer &&
                    s.isFromPeerDevice(sig.device)
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
        // The relay keeps only each sender's last blob: no candidate may follow the hangup, and the chosen device must
        // still get the whole list after the caller tells its siblings the call was answered elsewhere.
        val ice = iceSender?.takeIf { it.peer == peer }
        if (ice?.closed == true) ice.join()
        try {
            post(peer, signal)
        } finally {
            if (signal is CallSignal.Hangup && signal.reason.isElsewhere) ice?.repost()
        }
    }

    // The IceSender's path: it runs off the inbox, so it must not touch controller state.
    private suspend fun post(peer: String, signal: CallSignal) {
        val outbound = CallChannel.outbound(peer, signal.fromDevice(deviceId))
        log.i { "send ${describe(signal)} call=${signal.callId} to=$peer push=${outbound.push?.kind?.wire}" }
        signaling.send(outbound)
    }

    // Only the offer and the answer are load-bearing: every other signal is resent, superseded or covered by a timeout
    // (ring, connect, reconnect), and a cold push wake often posts before the network is up.
    private suspend fun sendBestEffort(peer: String, signal: CallSignal): Boolean =
        try {
            if (signal is CallSignal.Ice) post(peer, signal) else send(peer, signal)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "${describe(signal)} call=${signal.callId} not sent" }
            false
        }

    // A single unusable remote candidate (unresolvable mDNS, a transport the engine lacks) is routine; ICE uses the rest.
    private suspend fun addIce(engine: CallEngine, ice: CallEffect.AddIce) {
        try {
            engine.addIce(ice.candidate, ice.sdpMid, ice.sdpMLineIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "remote candidate rejected: ${ice.candidate}" }
            // A candidate that beat its restart offer is rejected; the repeat after the offer must not be deduped away.
            remoteIce.remove(ice)
        }
    }

    private fun describe(signal: CallSignal): String = when (signal) {
        is CallSignal.Hangup -> "hangup(${signal.reason})"
        is CallSignal.Offer -> "offer(seq=${signal.seq})"
        is CallSignal.Ice -> "ice(${signal.all.size})"
        else -> signal::class.simpleName.orEmpty()
    }

    private suspend fun process(event: CallEvent) {
        if (isReplayOfEndedCall(event)) return
        val before = _state.value
        val transition = machine.reduce(before, event, clock())
        _state.value = transition.state
        if (transition.state != before) _transitions.tryEmit(transition.state)
        if (transition.state.phase is CallPhase.Ended) transition.state.callId?.let(::rememberEnded)
        prefetchIceServers(transition.state)
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

    private class IceLease(val ice: CallIceServers, val untilMs: Long) {
        fun usableAt(nowMs: Long) = nowMs < untilMs
    }

    private suspend fun lease(peer: String): IceLease {
        val ice = iceServers(peer)
        val now = clock()
        return IceLease(ice, (now + ice.reuseForMs).let { if (it < now) Long.MAX_VALUE else it })
    }

    // Asked for as the call starts or rings, so placing it waits on one request at most and accepting it on none.
    private fun prefetchIceServers(s: CallState) {
        val callId = s.callId.takeIf { s.isActive }
        if (callId == iceFetch?.first) return
        iceFetch?.second?.cancel()
        iceFetch = null
        val peer = s.peer ?: return
        if (callId != null) iceFetch = callId to iceScope.async { lease(peer) }
    }

    private suspend fun iceServersForEngine(): IceLease {
        val peer = _state.value.peer ?: return IceLease(CallIceServers.None, 0)
        val prefetched = iceFetch?.second?.await()
        return if (prefetched != null && prefetched.usableAt(clock())) prefetched else lease(peer)
    }

    // Kept only once the engine takes it.
    private fun useIce(engine: CallEngine, lease: IceLease) {
        if (!engine.setIceServers(lease.ice.servers)) {
            log.w { "call=${_state.value.callId} engine refused ICE ${lease.ice}" }
            return
        }
        callIce = lease
        scheduleRenewal(engine, lease)
    }

    // Renewed ahead of expiry, so a reconnect after a network change doesn't wait on the network it just lost.
    private fun scheduleRenewal(engine: CallEngine, lease: IceLease) {
        renewJob?.cancel()
        renewJob = null
        val peer = _state.value.peer ?: return
        if (!lease.ice.hasLogin || lease.untilMs == Long.MAX_VALUE) return
        renewJob = iceScope.launch {
            delay(lease.untilMs - clock())
            inbox.trySend(Command.RenewIce(engine, lease(peer)))
        }
    }

    // A failed renewal keeps the servers the call has; the next restart asks again.
    private fun renewIce(engine: CallEngine, lease: IceLease) {
        if (engine !== this.engine) return
        log.i { "call=${_state.value.callId} ICE renewed: ${lease.ice}" }
        if (lease.ice.fromServer) useIce(engine, lease)
    }

    // A login only opens relays until it expires, and a restart gathers again.
    private suspend fun refreshIceServers(state: CallState) {
        val current = engine ?: return
        if (callIce?.usableAt(clock()) == true) return
        val fresh = lease(state.peer ?: return)
        log.i { "call=${state.callId} ICE servers for the restart: ${fresh.ice}" }
        if (fresh.ice.fromServer) useIce(current, fresh)
    }

    private suspend fun engine(): CallEngine = engine ?: run {
        val started = TimeSource.Monotonic.markNow()
        val lease = iceServersForEngine()
        val leased = started.elapsedNow()
        // The next call's camera, mic and audio mode must not race the last call's release of them.
        closingEngine?.join()
        val released = started.elapsedNow()
        engineFactory.create(lease.ice.servers).also {
            log.i { "engine build: ICE servers ${leased.inWholeMilliseconds}ms, last engine closed ${(released - leased).inWholeMilliseconds}ms, factory ${(started.elapsedNow() - released).inWholeMilliseconds}ms" }
            callIce = lease
            scheduleRenewal(it, lease)
        }
    }.also { created ->
        engine = created
        log.i { "call=${_state.value.callId} engine created on ${Platform.osName} ${Platform.osVersion}, ICE ${callIce?.ice}" }
        _mediaDevices.value = created.mediaDevices
        remoteReady = false
        engineEvents = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.events.collect { post(CallEvent.Media(it)) }
        }
        engineVideos = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(created.localVideo, created.remoteVideo, ::CallVideos).collect { _videos.value = it }
        }
    }

    private fun reportMissingCamera(videoRequested: Boolean) {
        if (videoRequested && engine?.localVideo?.value == null) post(CallEvent.NoLocalVideoTrack)
    }

    private suspend fun run(effect: CallEffect, state: CallState) {
        try {
            when (effect) {
                is CallEffect.SendSignal -> sendBestEffort(effect.peer, effect.signal)
                is CallEffect.CreateOffer -> {
                    val sdp = engine().createOffer(effect.video)
                    reportMissingCamera(effect.video)
                    offerSdp = sdp
                    offerVideo = effect.video
                    offerSeq = 0
                    heldIceInOffer.clear()
                    publishedOfferSdp = sdp
                    send(state.peer!!, CallSignal.Offer(state.callId!!, sdp, effect.video, iceBatch = true))
                }
                is CallEffect.TrickleIce -> trickle(state, listOf(LocalCandidate(effect.candidate, effect.sdpMid, effect.sdpMLineIndex)))
                is CallEffect.HoldIce -> {
                    heldIce += LocalCandidate(effect.candidate, effect.sdpMid, effect.sdpMLineIndex)
                    scheduleOfferRepublish(state.callId!!)
                }
                is CallEffect.CreateAnswer -> {
                    val sdp = engine().answer(effect.offerSdp, effect.video)
                    reportMissingCamera(effect.video)
                    offerSdp = effect.offerSdp
                    answerSdp = sdp
                    send(state.peer!!, CallSignal.Answer(state.callId!!, sdp, offerSeq = effect.offerSeq, iceBatch = true))
                    markRemoteReady()
                    announceVerification(state.callId)
                }
                is CallEffect.ApplyRemoteAnswer -> {
                    engine().applyRemote(effect.sdp)
                    restartOfferSdp = null
                    markRemoteReady()
                    flushHeldIce(state, effect.offerSeq)
                    if (answerSdp == null) {
                        answerSdp = effect.sdp
                        announceVerification(state.callId!!)
                    }
                }
                CallEffect.RestartIce -> {
                    // Resend the unanswered offer: a fresh one would orphan an answer already in flight for it.
                    val sdp = restartOfferSdp ?: run {
                        refreshIceServers(state)
                        newIceGeneration()
                        engine().restartIce()
                    }.also { restartOfferSdp = it }
                    sendBestEffort(state.peer!!, CallSignal.Restart(state.callId!!, sdp))
                }
                is CallEffect.AnswerRestart -> {
                    refreshIceServers(state)
                    if (effect.offerSdp != answeredRestartOffer) newIceGeneration()
                    answeredRestartOffer = effect.offerSdp
                    val sdp = engine().answerRestart(effect.offerSdp)
                    sendBestEffort(state.peer!!, CallSignal.RestartAnswer(state.callId!!, sdp))
                }
                is CallEffect.AddIce -> when {
                    !remoteIce.add(effect) -> Unit
                    !remoteReady -> bufferedIce += effect
                    else -> engine?.let { addIce(it, effect) }
                }
                is CallEffect.SetMuted -> engine?.setMuted(effect.muted)
                is CallEffect.SetVideo -> engine?.setVideo(effect.on)
                CallEffect.AddVideo -> if (engine?.addVideo() != true) {
                    post(CallEvent.NoLocalVideoTrack)
                    post(CallEvent.SetVideo(false))
                }
                CallEffect.SwitchCamera -> engine?.switchCamera()
                is CallEffect.SetRoute -> engine?.setAudioRoute(effect.route)
                is CallEffect.StartTimer -> startTimer(effect.timer, effect.callId)
                CallEffect.CancelTimers -> cancelTimers()
                CallEffect.ReleaseEngine -> releaseEngine()
            }
        } catch (e: CallEngineException) {
            log.w { "engine failed: ${e.reason} ${e.message}" }
            post(CallEvent.EngineError(e.reason))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Native engines throw Errors (UnsatisfiedLinkError); one escaping here kills the inbox loop.
            log.w(e) { "call effect $effect failed" }
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
        val lines = sdp.lines().mapTo(HashSet()) { it.trim() }
        heldIceInOffer[offerSeq] = held.mapNotNullTo(HashSet()) { c -> c.candidate.takeIf { "a=${it.trim().removePrefix("a=")}" in lines } }
        log.i { "republishing offer call=$callId seq=$offerSeq candidates=${held.size}" }
        sendBestEffort(s.peer!!, CallSignal.Offer(callId, sdp, offerVideo, offerSeq, iceBatch = true))
        if (heldIce.size > publishedIce) scheduleOfferRepublish(callId)
    }

    private fun flushHeldIce(state: CallState, answeredSeq: Int?) {
        republishJob?.cancel()
        republishJob = null
        val carried = answeredSeq?.let { heldIceInOffer[it] }.orEmpty()
        val pending = heldIce.filter { it.candidate !in carried }
        if (heldIce.isNotEmpty()) log.i { "answer to offer seq=$answeredSeq: trickling ${pending.size} of ${heldIce.size} held candidates call=${state.callId}" }
        heldIce.clear()
        publishedIce = 0
        heldIceInOffer.clear()
        if (pending.isNotEmpty()) trickle(state, pending)
    }

    private fun trickle(state: CallState, candidates: List<LocalCandidate>) {
        val sender = iceSender?.takeUnless { it.closed }
            ?: IceSender(state.peer!!, state.callId!!, state.peerIceBatch).also { iceSender = it }
        sender.add(candidates)
    }

    // A restart's candidates replace the last generation's on both sides: the old list is not posted again, and the new one
    // is not deduped against it.
    private suspend fun newIceGeneration() {
        iceSender?.let {
            iceSender = null
            it.close()
            it.join()
        }
        remoteIce.clear()
    }

    // Off the inbox, which a relay round trip must not hold; one post at a time, each repeating every candidate before it,
    // since the relay keeps only the last blob.
    private inner class IceSender(val peer: String, private val callId: String, private val batch: Boolean) {
        private val progress = MutableStateFlow(IceProgress())
        private val job = scope.launch { progress.collect { postUnsent() } }
        var closed = false
            private set

        fun add(candidates: List<LocalCandidate>) = progress.update { it.copy(all = it.all + candidates) }

        // A single-candidate post is not the whole list, so only a batch is posted again.
        fun repost() {
            if (batch) progress.update { it.copy(sent = 0, reposts = it.reposts + 1) }
        }

        private suspend fun postUnsent() {
            var retryMs = ICE_RETRY_MS
            var attempts = 0
            while (true) {
                val p = progress.value
                if (p.sent >= p.all.size) return
                val c = p.all[p.sent]
                val signal = if (batch) CallSignal.Ice.of(callId, p.all) else CallSignal.Ice(callId, c.candidate, c.sdpMid, c.sdpMLineIndex)
                if (sendBestEffort(peer, signal)) {
                    val done = if (batch) p.all.size else p.sent + 1
                    // A repost asked for while this one was in flight keeps its reset.
                    progress.update { if (it.reposts == p.reposts) it.copy(sent = done) else it }
                    retryMs = ICE_RETRY_MS
                    attempts = 0
                } else {
                    // A refusal that never clears stops here; the next candidate or repost tries again.
                    if (++attempts >= ICE_RETRY_ATTEMPTS) return
                    delay(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(ICE_RETRY_MAX_MS)
                }
            }
        }

        suspend fun join() = job.join()

        fun close() {
            closed = true
            job.cancel()
        }
    }

    private fun announceVerification(callId: String) {
        val code = CallVerification.code(offerSdp ?: return, answerSdp ?: return) ?: return
        post(CallEvent.Verification(callId, code))
    }

    private suspend fun markRemoteReady() {
        remoteReady = true
        val pending = bufferedIce.toList()
        bufferedIce.clear()
        val current = engine ?: return
        pending.forEach { addIce(current, it) }
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

    // ATOMIC: teardown runs this while the scope is cancelling, and a DEFAULT launch would then never close the engine, leaving camera and mic held.
    @OptIn(DelicateCoroutinesApi::class)
    private fun releaseEngine() {
        // Left set: the hangup that follows waits for the cancelled post before it goes out.
        iceSender?.close()
        engineEvents?.cancel()
        engineEvents = null
        engineVideos?.cancel()
        engineVideos = null
        _videos.value = CallVideos()
        _mediaDevices.value = null
        // Closing takes ~2 s on a Redmi (camera stop, peer close); the hangup that follows must not wait for it.
        engine?.let { released ->
            closingEngine = scope.launch(start = CoroutineStart.ATOMIC) { released.close() }
        }
        engine = null
        callIce = null
        renewJob?.cancel()
        renewJob = null
        remoteReady = false
        bufferedIce.clear()
        remoteIce.clear()
        answeredRestartOffer = null
        heldIce.clear()
        publishedIce = 0
        offerSeq = 0
        heldIceInOffer.clear()
        publishedOfferSdp = null
        offerSdp = null
        answerSdp = null
        restartOfferSdp = null
    }

    private companion object {
        const val TAG = "CallController"
        const val ENDED_CALLS_REMEMBERED = 32
        const val QUIT_HANGUP_BUDGET_MS = 2_000L
        const val ICE_RETRY_MS = 1_000L
        // Well inside the connect timeout, so a network that comes back is used within seconds.
        const val ICE_RETRY_MAX_MS = 4_000L
        const val ICE_RETRY_ATTEMPTS = 8
    }
}

private data class IceProgress(val all: List<LocalCandidate> = emptyList(), val sent: Int = 0, val reposts: Int = 0)
