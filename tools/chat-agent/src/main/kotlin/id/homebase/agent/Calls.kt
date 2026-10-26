package id.homebase.agent

import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.liverelay.LiveRelayProvider
import id.homebase.api.client.websockets.StunUrlStore
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallEngineFactory
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.RING_TIMEOUT_MS
import id.homebase.chat.services.calling.WebRtcJavaCallEngine
import id.homebase.chat.services.content.MessageContent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withTimeoutOrNull

const val CALL_SAMPLE_RATE = 48_000
const val CALL_FRAME_MS = 10
const val CALL_SETTLE_MS = 800L
const val CALL_TAIL_MS = 1_500L
const val CALL_CONNECT_SLACK_MS = 10_000L
private const val REMOTE_RING_FRAMES = 500
private const val PCM_DECODE_MS = 60_000L

class PcmPacer(sampleRate: Int, private val push: (ByteArray) -> Unit) {
    private class Item(val pcm: ByteArray, val done: CompletableDeferred<Unit>) {
        var offset = 0
    }

    private val frameBytes = sampleRate / 1000 * CALL_FRAME_MS * 2
    private val silence = ByteArray(frameBytes)
    private val queue = ArrayDeque<Item>()

    fun enqueue(pcm: ByteArray): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        if (pcm.isEmpty()) return done.also { it.complete(Unit) }
        synchronized(queue) { queue.addLast(Item(pcm, done)) }
        return done
    }

    fun cancel(done: CompletableDeferred<Unit>) {
        synchronized(queue) { queue.removeAll { it.done === done } }
    }

    fun flush() {
        val dropped = synchronized(queue) { queue.toList().also { queue.clear() } }
        dropped.forEach { it.done.complete(Unit) }
    }

    val idle get() = synchronized(queue) { queue.isEmpty() }

    fun tick() {
        val finished = ArrayList<CompletableDeferred<Unit>>()
        val frame = synchronized(queue) {
            val head = queue.firstOrNull() ?: return@synchronized silence
            val out = ByteArray(frameBytes)
            val n = minOf(frameBytes, head.pcm.size - head.offset)
            System.arraycopy(head.pcm, head.offset, out, 0, n)
            head.offset += n
            if (head.offset >= head.pcm.size) {
                queue.removeFirst()
                finished += head.done
            }
            out
        }
        push(frame)
        finished.forEach { it.complete(Unit) }
    }
}

class PcmPlayer(private val sampleRate: Int = CALL_SAMPLE_RATE) {
    private val pacer = PcmPacer(sampleRate) { frame -> source.pushAudio(frame, 16, sampleRate, 1, frame.size / 2) }
    private var executor: ScheduledExecutorService? = null

    val source: CustomAudioSource by lazy { CustomAudioSource().also { startClock() } }

    @Synchronized private fun startClock() {
        executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "call-pcm-player").apply { isDaemon = true } }.also {
            it.scheduleAtFixedRate({ runCatching { pacer.tick() } }, 0, CALL_FRAME_MS.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    suspend fun play(pcm: ByteArray) {
        val done = pacer.enqueue(pcm)
        try {
            done.await()
        } catch (e: CancellationException) {
            pacer.cancel(done)
            throw e
        }
    }

    fun flush() = pacer.flush()

    @Synchronized fun stop() {
        pacer.flush()
        executor?.shutdownNow()
        executor = null
    }
}

fun rms(samples: ShortArray): Double {
    if (samples.isEmpty()) return 0.0
    var sum = 0.0
    for (s in samples) sum += s.toDouble() * s
    return sqrt(sum / samples.size) / Short.MAX_VALUE
}

class RemoteAudio : AudioTrackSink {
    private val ring = MutableSharedFlow<ShortArray>(extraBufferCapacity = REMOTE_RING_FRAMES, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: Flow<ShortArray> = ring.asSharedFlow()

    @Volatile var level = 0.0
        private set

    override fun onData(data: ByteArray, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int) {
        if (bitsPerSample != 16) return
        val shorts = ShortArray(minOf(data.size / 2, frames * channels))
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        level = rms(shorts)
        ring.tryEmit(shorts)
    }
}

fun headlessEngineFactory(player: PcmPlayer, remote: RemoteAudio) = CallEngineFactory {
    WebRtcJavaCallEngine(it, HeadlessAudioDeviceModule(), audioSource = { player.source }, remoteAudioSink = remote, renderVideo = false)
}

fun callOutcomeFor(reason: CallEndReason): CallOutcome = when (reason) {
    CallEndReason.Declined, CallEndReason.DeclinedElsewhere, is CallEndReason.Hangup -> CallOutcome.DECLINED
    CallEndReason.Missed, CallEndReason.Busy, CallEndReason.Cancelled -> CallOutcome.MISSED
    CallEndReason.AnsweredElsewhere -> CallOutcome.ANSWERED
    is CallEndReason.Failed -> CallOutcome.FAILED
}

class OwnerCallDriver(
    private val operators: Set<OdinId>,
    private val enabled: Boolean,
    private val transport: Transport,
    private val calls: CallSession,
    private val synthesize: suspend (String) -> ByteArray?,
    private val play: suspend (ByteArray) -> Unit,
    private val connectTimeoutMs: Long = RING_TIMEOUT_MS + CALL_CONNECT_SLACK_MS,
    private val settleMs: Long = CALL_SETTLE_MS,
    private val tailMs: Long = CALL_TAIL_MS,
    private val log: (String) -> Unit = {},
) : OwnerCaller {
    private val single = Mutex()

    override suspend fun call(peer: OdinId, say: String): CallOutcome {
        if (peer !in operators) return CallOutcome.UNAVAILABLE.also { log("call refused: $peer is not an operator") }
        if (!enabled) return CallOutcome.UNAVAILABLE.also { log("call refused: calls=off") }
        if (transport == Transport.POLL) return CallOutcome.UNAVAILABLE.also { log("call refused: transport=poll has no websocket for call signaling") }
        if (!single.tryLock()) return CallOutcome.UNAVAILABLE.also { log("call refused: another call is in progress") }
        try {
            val pcm = try {
                synthesize(say)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("call aborted: synthesis failed: ${e.message}")
                null
            }
            if (pcm == null || pcm.isEmpty()) return CallOutcome.FAILED.also { log("call aborted: nothing to say") }
            return dial(peer, pcm)
        } finally {
            single.unlock()
        }
    }

    private suspend fun dial(peer: OdinId, pcm: ByteArray): CallOutcome {
        val previous = calls.state.value.callId
        try {
            calls.startCall(peer.domainName, video = false)
            val settled = withTimeoutOrNull(connectTimeoutMs) {
                calls.state.first { it.callId != null && it.callId != previous && (it.phase == CallPhase.Connected || it.phase is CallPhase.Ended) }
            } ?: return (if (calls.state.value.callId == previous) CallOutcome.FAILED else CallOutcome.MISSED).also { log("call to $peer did not connect in ${connectTimeoutMs / 1000}s") }
            (settled.phase as? CallPhase.Ended)?.let { return callOutcomeFor(it.reason).also { o -> log("call to $peer ended before connecting: ${it.reason} -> $o") } }
            log("call to $peer connected, speaking ${pcm.size / (CALL_SAMPLE_RATE * 2 / 1000)} ms")
            delay(settleMs)
            coroutineScope {
                val speaking = launch { play(pcm) }
                val watcher = launch { calls.state.first { it.phase is CallPhase.Ended }; speaking.cancel() }
                speaking.join()
                watcher.cancel()
            }
            if (calls.state.value.phase !is CallPhase.Ended) delay(tailMs)
            return CallOutcome.ANSWERED
        } finally {
            withContext(NonCancellable) { calls.hangup() }
        }
    }
}

class AgentCalls(
    session: Session,
    eventBus: EventBus,
    scope: CoroutineScope,
    private val config: AgentConfig,
    speaker: suspend (String) -> OutVoice?,
    sendTyped: suspend (Uuid, MessageContent) -> Unit,
    private val log: (String) -> Unit = {},
    ffmpeg: Ffmpeg = DEFAULT_FFMPEG,
) : OwnerCaller {
    private val player = PcmPlayer()
    private val remote = RemoteAudio()
    private val relay = LiveRelayProvider(session.http, session.credentials)
    private val service = CallService(
        credentialsManager = session.credentials,
        eventBus = eventBus,
        relay = { channelKey, recipients, blob, push -> relay.relay(channelKey, recipients, blob, push, wakeCause = null) },
        engineFactory = headlessEngineFactory(player, remote),
        stunUrls = StunUrlStore(eventBus, session.credentials.credentialsFlow.map { it?.domain?.domainName }, scope),
        scope = scope,
        recordEvent = { peer, event ->
            val conversation = config.allowlist.directWith(session.identity, OdinId(peer)) ?: error("no 1:1 with $peer to log the call in")
            sendTyped(conversation, MessageContent.CallEvent(event))
        },
    )
    private val driver = OwnerCallDriver(
        operators = config.operators,
        enabled = config.calls,
        transport = config.transport,
        calls = service,
        synthesize = { text -> speaker(text)?.let { decode(ffmpeg, it) } },
        play = { player.play(it) },
        log = log,
    )

    init {
        scope.launch {
            service.state.filter { it.phase == CallPhase.Incoming && it.direction == CallDirection.Incoming }.collect {
                log("declining incoming call from ${it.peer}")
                service.decline()
            }
        }
        scope.launch {
            remote.frames.map { rms(it) }.filter { it > 0.01 }.first()
            log("remote audio level ${"%.3f".format(java.util.Locale.ROOT, remote.level)}")
        }
    }

    override suspend fun call(peer: OdinId, say: String) = driver.call(peer, say)

    fun close() {
        player.stop()
    }

    private suspend fun decode(ffmpeg: Ffmpeg, voice: OutVoice): ByteArray? = withContext(Dispatchers.IO) {
        val dir = tempDir("callpcm")
        try {
            val input = File(dir, "say.${voice.name.substringAfterLast('.', "m4a")}").also { it.writeBytes(voice.bytes) }
            ffmpeg.pcm(input, CALL_SAMPLE_RATE, PCM_DECODE_MS)
        } finally {
            dir.deleteRecursively()
        }
    }
}

fun buildAgentCalls(session: Session, eventBus: EventBus, scope: CoroutineScope, config: AgentConfig, log: (String) -> Unit): AgentCalls? {
    if (!config.calls) return null
    val command = config.tts ?: return null
    return AgentCalls(
        session, eventBus, scope, config,
        speaker = shellSpeaker(command, ttsEnvironment(config), config.ttsTimeoutMs, log, config.ttsMaxChars),
        sendTyped = { conversation, content -> sendToConversation(session, config.allowlist, conversation, "", typed = content) },
        log = log,
    )
}

private const val CALL_CLI_CONNECT_MS = 30_000L

suspend fun callOwner(profile: String, to: String, say: String, verbose: Boolean) {
    val session = openSession(profile)
    val config = loadConfig(profile, session.identity)
    val peer = OdinId(to)
    require(peer in config.operators) { "$to is not in operators, the agent only calls operators" }
    require(config.calls) { "calls are off: set calls=on (it needs tts= and a websocket transport) in the profile config" }
    refreshAllowlist(session, config.allowlist)
    coroutineScope {
        val bus = EventBus()
        val calls = buildAgentCalls(session, bus, this, config, ::println) ?: error("calls are off")
        val waker = PollWaker()
        startDoorbell(this, config.transport, sessionConnector(session, verbose, ::println, bus), waker, ::println)
        withTimeout(CALL_CLI_CONNECT_MS) { while (!waker.connected) delay(200) }
        println("outcome: ${calls.call(peer, say)}")
        calls.close()
        coroutineContext.job.cancelChildren()
    }
}
