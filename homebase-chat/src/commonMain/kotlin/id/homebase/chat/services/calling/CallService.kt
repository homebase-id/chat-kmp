package id.homebase.chat.services.calling

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.StunUrlStore
import id.homebase.chat.calling.CallEventDescriptor
import id.homebase.chat.calling.CallOutcome
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
) {
    val available: Boolean get() = engineFactory != null

    private val controller = MutableStateFlow<CallController?>(null)
    private var controllerJob: Job? = null

    val state: StateFlow<CallState> = controller
        .flatMapLatest { it?.state ?: flowOf(CallState()) }
        .stateIn(scope, SharingStarted.Eagerly, CallState())

    val videos: StateFlow<CallVideos> = controller
        .flatMapLatest { it?.videos ?: flowOf(CallVideos()) }
        .stateIn(scope, SharingStarted.Eagerly, CallVideos())

    init {
        if (engineFactory != null) {
            scope.launch {
                var recorded: String? = null
                state.collect { s ->
                    val ended = s.phase as? CallPhase.Ended ?: return@collect
                    val callId = s.callId ?: return@collect
                    if (callId == recorded || s.direction != CallDirection.Outgoing) return@collect
                    recorded = callId
                    val peer = s.peer ?: return@collect
                    callEventFor(ended.reason, s, nowMs())?.let { runCatching { recordEvent(peer, it) } }
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

    // A push can land before the identity has loaded (cold FCM/PushKit wake); replay it once bound.
    private var pendingRing: Pair<String, String>? = null

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
        ).also { created -> pendingRing?.let { (callId, from) -> created.onPushRing(callId, from) } }
        pendingRing = null
    }

    fun startCall(peer: String, video: Boolean) = controller.value?.startCall(peer, video)
    fun accept() = controller.value?.accept()
    fun decline() = controller.value?.decline()
    fun hangup() = controller.value?.hangup()
    fun setMuted(muted: Boolean) = controller.value?.setMuted(muted)
    fun setVideo(on: Boolean) = controller.value?.setVideo(on)
    fun switchCamera() = controller.value?.switchCamera()
    fun setAudioRoute(route: AudioRoute) = controller.value?.setAudioRoute(route)
    fun onPermissionDenied() = controller.value?.onPermissionDenied()
    fun onPushRing(callId: String, from: String) {
        val current = controller.value
        if (current != null) current.onPushRing(callId, from) else pendingRing = callId to from
    }

    fun onPushHangup(callId: String) {
        if (pendingRing?.first == callId) pendingRing = null
        controller.value?.onPushHangup(callId)
    }
}

/** One log entry per call, written by the caller: the receiving side renders it from the message author. */
internal fun callEventFor(reason: CallEndReason, state: CallState, nowMs: Long): CallEventDescriptor? {
    val video = state.media.localVideo || state.media.remoteVideo
    val seconds = state.connectedAtMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) } ?: 0L
    return when {
        state.connectedAtMs != null -> CallEventDescriptor(CallOutcome.Completed, video, seconds)
        reason == CallEndReason.Missed -> CallEventDescriptor(CallOutcome.NoAnswer, video)
        reason == CallEndReason.Declined -> CallEventDescriptor(CallOutcome.Declined, video)
        reason == CallEndReason.Cancelled -> CallEventDescriptor(CallOutcome.Cancelled, video)
        reason == CallEndReason.Busy -> CallEventDescriptor(CallOutcome.Busy, video)
        else -> null
    }
}
