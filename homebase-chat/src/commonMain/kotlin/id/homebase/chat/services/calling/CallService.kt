package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.StunUrlStore
import id.homebase.chat.calling.CallEventDescriptor
import id.homebase.chat.calling.CallOutcome
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The one [CallController] per signed-in identity. UI, push receivers and the CallKit/Telecom glue
 * all go through here, so a ring from a push and a ring from a socket offer meet in one state machine.
 * [engineFactory] is null where calling is unsupported (and [available] is then false).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallService(
    credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val relay: LiveRelaySend,
    private val engineFactory: CallEngineFactory?,
    private val stunUrls: StunUrlStore,
    private val scope: CoroutineScope,
    private val recordEvent: suspend (peer: String, event: CallEventDescriptor) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CallSession, CallPushTarget, CallRingPushTarget {
    val available: Boolean get() = engineFactory != null

    private val controller = MutableStateFlow<CallController?>(null)
    private var controllerJob: Job? = null

    override val state: StateFlow<CallState> = controller
        .flatMapLatest { it?.state ?: flowOf(CallState()) }
        .stateIn(scope, SharingStarted.Eagerly, CallState())

    override val videos: StateFlow<CallVideos> = controller
        .flatMapLatest { it?.videos ?: flowOf(CallVideos()) }
        .stateIn(scope, SharingStarted.Eagerly, CallVideos())

    // Declared before init: the init-launched loop reads it, and a field initialised later is null there.
    // A push can land on any thread before the identity has loaded (cold FCM/PushKit wake); the channel keeps
    // ring and hangup in arrival order and replays them to whichever controller is bound.
    private val pushes = Channel<Push>(Channel.UNLIMITED)

    init {
        if (engineFactory != null) {
            scope.launch { recordCallEvents(state, nowMs, recordEvent) }
            scope.launch {
                for (push in pushes) {
                    val target = controller.filterNotNull().first()
                    when (push) {
                        is Push.Ring -> {
                            val processed = target.pushRing(push.callId, push.from)
                            push.ack?.let { ack ->
                                processed.invokeOnCompletion { error -> if (error == null) ack.complete(processed.getCompleted()) else ack.cancel() }
                            }
                        }
                        is Push.Hangup -> target.onPushHangup(push.callId)
                    }
                }
            }
            scope.launch {
                credentialsManager.credentialsFlow
                    .map { it?.domain?.domainName }
                    .distinctUntilChanged()
                    .collect { bind(it) }
            }
        }
    }

    private sealed interface Push {
        data class Ring(val callId: String, val from: String, val ack: CompletableDeferred<CallState>? = null) : Push
        data class Hangup(val callId: String) : Push
    }

    private fun bind(selfOdinId: String?) {
        controller.value?.close()
        controllerJob?.cancel()
        controller.value = null
        if (selfOdinId == null || engineFactory == null) return
        val job = Job(scope.coroutineContext[Job])
        controllerJob = job
        controller.value = CallController(
            selfOdinId = selfOdinId,
            signaling = LiveRelayCallSignaling(selfOdinId, relay, eventBus),
            engineFactory = engineFactory,
            scope = CoroutineScope(scope.coroutineContext + job),
            selfLabel = selfOdinId,
            stunUrls = { stunUrls.urls.value },
        )
    }

    override fun startCall(peer: String, video: Boolean) { controller.value?.startCall(peer, video) }
    override fun accept() { controller.value?.accept() }
    override fun decline() { controller.value?.decline() }
    override fun hangup() { controller.value?.hangup() }
    override fun setMuted(muted: Boolean) { controller.value?.setMuted(muted) }
    override fun setVideo(on: Boolean) { controller.value?.setVideo(on) }
    override fun switchCamera() { controller.value?.switchCamera() }
    override fun setAudioRoute(route: AudioRoute) { controller.value?.setAudioRoute(route) }
    fun onPermissionDenied() = controller.value?.onPermissionDenied()
    override fun onPushRing(callId: String, from: String) { pushes.trySend(Push.Ring(callId, from)) }

    override fun pushRing(callId: String, from: String): Deferred<CallState> =
        CompletableDeferred<CallState>().also { pushes.trySend(Push.Ring(callId, from, it)) }

    override fun onPushHangup(callId: String) { pushes.trySend(Push.Hangup(callId)) }
}

internal suspend fun recordCallEvents(
    states: Flow<CallState>,
    nowMs: () -> Long,
    recordEvent: suspend (peer: String, event: CallEventDescriptor) -> Unit,
) {
    var recorded: String? = null
    states.collect { s ->
        val ended = s.phase as? CallPhase.Ended ?: return@collect
        val callId = s.callId ?: return@collect
        if (callId == recorded || s.direction != CallDirection.Outgoing) return@collect
        recorded = callId
        val peer = s.peer ?: return@collect
        val event = callEventFor(ended.reason, s, nowMs()) ?: return@collect
        try {
            recordEvent(peer, event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = "CallService", throwable = e) { "Failed to record call event for $callId" }
        }
    }
}

/** One log entry per call, written by the caller: the receiving side renders it from the message author. */
internal fun callEventFor(reason: CallEndReason, state: CallState, nowMs: Long): CallEventDescriptor? {
    val video = state.placedWithVideo
    val seconds = state.connectedAtMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) } ?: 0L
    return when {
        state.connectedAtMs != null -> CallEventDescriptor(CallOutcome.Completed, video, seconds)
        reason == CallEndReason.Missed -> CallEventDescriptor(CallOutcome.NoAnswer, video)
        reason == CallEndReason.Declined -> CallEventDescriptor(CallOutcome.Declined, video)
        reason == CallEndReason.Cancelled -> CallEventDescriptor(CallOutcome.Cancelled, video)
        reason == CallEndReason.Busy -> CallEventDescriptor(CallOutcome.Busy, video)
        reason is CallEndReason.Failed -> CallEventDescriptor(CallOutcome.Failed, video)
        reason is CallEndReason.Hangup -> CallEventDescriptor(if (reason.remote) CallOutcome.Declined else CallOutcome.Cancelled, video)
        else -> null
    }
}
