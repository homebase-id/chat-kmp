package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
) : CallSession {
    private val machine = CallStateMachine(selfOdinId)
    private val _state = MutableStateFlow(CallState())
    override val state: StateFlow<CallState> = _state.asStateFlow()

    private val _videos = MutableStateFlow(CallVideos())
    override val videos: StateFlow<CallVideos> = _videos.asStateFlow()

    // Everything below the inbox is touched only from the inbox loop; other threads just post.
    private sealed interface Command {
        class Event(val event: CallEvent) : Command
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

    private val collectors: List<Job>

    init {
        collectors = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                signaling.incoming.collect { post(CallEvent.Signal(it)) }
            },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    for (command in inbox) {
                        when (command) {
                            is Command.Event -> process(command.event)
                            Command.Close -> break
                        }
                    }
                } finally {
                    withContext(NonCancellable) { teardown() }
                }
            },
        )
    }

    fun close() {
        inbox.trySend(Command.Close)
    }

    private fun teardown() {
        collectors.forEach { it.cancel() }
        inbox.close()
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

    fun onPushRing(callId: String, from: String) = post(CallEvent.PushRing(callId, from))
    fun onPushHangup(callId: String) = post(CallEvent.PushHangup(callId))

    private fun post(event: CallEvent) {
        inbox.trySend(Command.Event(event))
    }

    private suspend fun send(peer: String, signal: CallSignal) =
        signaling.send(CallChannel.outbound(peer, signal, selfLabel))

    private suspend fun process(event: CallEvent) {
        val transition = machine.reduce(_state.value, event, clock())
        _state.value = transition.state
        for (effect in transition.effects) run(effect, transition.state)
    }

    private fun engine(): CallEngine = engine ?: engineFactory.create(stunUrls()).also { created ->
        engine = created
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
                is CallEffect.SendSignal -> send(effect.peer, effect.signal)
                is CallEffect.CreateOffer -> {
                    val sdp = engine().createOffer(effect.video)
                    offerSdp = sdp
                    send(state.peer!!, CallSignal.Offer(state.callId!!, sdp, effect.video))
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
                    markRemoteReady()
                    if (answerSdp == null) {
                        answerSdp = effect.sdp
                        announceVerification(state.callId!!)
                    }
                }
                CallEffect.RestartIce -> {
                    val sdp = engine().restartIce()
                    send(state.peer!!, CallSignal.Restart(state.callId!!, sdp))
                }
                is CallEffect.AnswerRestart -> {
                    val sdp = engine().answerRestart(effect.offerSdp)
                    send(state.peer!!, CallSignal.RestartAnswer(state.callId!!, sdp))
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
    }

    private fun releaseEngine() {
        engineEvents?.cancel()
        engineEvents = null
        engineVideos?.cancel()
        engineVideos = null
        _videos.value = CallVideos()
        engine?.close()
        engine = null
        remoteReady = false
        bufferedIce.clear()
        offerSdp = null
        answerSdp = null
    }

    private companion object {
        const val TAG = "CallController"
    }
}
