package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CallController(
    selfOdinId: String,
    private val signaling: CallSignaling,
    private val engineFactory: CallEngineFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val machine = CallStateMachine(selfOdinId)
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val inbox = Channel<CallEvent>(Channel.UNLIMITED)
    private var engine: CallEngine? = null
    private var engineEvents: Job? = null
    private var remoteReady = false
    private val bufferedIce = mutableListOf<CallEffect.AddIce>()
    private val timers = mutableMapOf<CallTimer, Job>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaling.incoming.collect { inbox.trySend(CallEvent.Signal(it)) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (event in inbox) process(event)
        }
    }

    fun startCall(peer: String, video: Boolean, callId: String = Uuid.random().toString()) =
        post(CallEvent.UserCall(callId, peer, video))

    fun accept() = post(CallEvent.UserAccept)
    fun decline() = post(CallEvent.UserDecline)
    fun hangup() = post(CallEvent.UserHangup)
    fun setMuted(muted: Boolean) = post(CallEvent.SetMuted(muted))
    fun setVideo(on: Boolean) = post(CallEvent.SetVideo(on))
    fun switchCamera() = post(CallEvent.SwitchCamera)
    fun setAudioRoute(route: AudioRoute) = post(CallEvent.SetRoute(route))
    fun onPermissionDenied() = post(CallEvent.EngineError(FailReason.Permission))

    private fun post(event: CallEvent) {
        inbox.trySend(event)
    }

    private suspend fun process(event: CallEvent) {
        val transition = machine.reduce(_state.value, event, clock())
        _state.value = transition.state
        for (effect in transition.effects) run(effect, transition.state)
    }

    private fun engine(): CallEngine = engine ?: engineFactory.create().also { created ->
        engine = created
        remoteReady = false
        engineEvents = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.events.collect { post(CallEvent.Media(it)) }
        }
    }

    private suspend fun run(effect: CallEffect, state: CallState) {
        try {
            when (effect) {
                is CallEffect.SendSignal -> signaling.send(effect.peer, effect.signal)
                is CallEffect.CreateOffer -> {
                    val sdp = engine().createOffer(effect.video)
                    signaling.send(state.peer!!, CallSignal.Offer(state.callId!!, sdp, effect.video))
                }
                is CallEffect.CreateAnswer -> {
                    val sdp = engine().answer(effect.offerSdp, effect.video)
                    signaling.send(state.peer!!, CallSignal.Answer(state.callId!!, sdp))
                    markRemoteReady()
                }
                is CallEffect.ApplyRemoteAnswer -> {
                    engine().applyRemote(effect.sdp)
                    markRemoteReady()
                }
                is CallEffect.AddIce ->
                    if (remoteReady) engine?.addIce(effect.candidate, effect.sdpMid, effect.sdpMLineIndex)
                    else bufferedIce += effect
                is CallEffect.SetMuted -> engine?.setMuted(effect.muted)
                is CallEffect.SetVideo -> engine?.setVideo(effect.on)
                CallEffect.SwitchCamera -> engine?.switchCamera()
                is CallEffect.SetRoute -> engine?.setAudioRoute(effect.route)
                is CallEffect.StartTimer -> startTimer(effect.timer, effect.callId)
                CallEffect.CancelTimers -> {
                    timers.values.forEach { it.cancel() }
                    timers.clear()
                }
                CallEffect.ReleaseEngine -> releaseEngine()
            }
        } catch (e: CallEngineException) {
            Logger.w(tag = TAG) { "engine failed: ${e.reason} ${e.message}" }
            post(CallEvent.EngineError(e.reason))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "call effect $effect failed" }
            post(CallEvent.EngineError(FailReason.Other))
        }
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
                CallTimer.Ring -> delay(RING_TIMEOUT_MS)
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

    private fun releaseEngine() {
        engineEvents?.cancel()
        engineEvents = null
        engine?.close()
        engine = null
        remoteReady = false
        bufferedIce.clear()
    }

    private companion object {
        const val TAG = "CallController"
    }
}
