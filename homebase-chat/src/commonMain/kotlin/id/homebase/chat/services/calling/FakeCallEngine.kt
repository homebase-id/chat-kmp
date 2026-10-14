package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import id.homebase.core.util.initials

data class FakeVideo(val initials: String, val size: Pair<Int, Int>? = null) : VideoHandle {
    override val frameSize: StateFlow<Pair<Int, Int>?> = MutableStateFlow(size)
}

data class FakeCallScript(
    val connectAfterMs: Long = 1_000,
    val dropAtMs: Long? = null,
    val recoverAfterDropMs: Long? = null,
    val remoteVideoOffAtMs: Long? = null,
    val failConnect: Boolean = false,
    val remoteVideoOnConnect: Boolean = false,
    val failOnCreateOffer: Throwable? = null,
    val localCandidates: List<String> = listOf(FakeCallEngine.FAKE_CANDIDATE),
)

class FakeCallEngine(
    private val scope: CoroutineScope,
    private val script: FakeCallScript = FakeCallScript(),
    peerLabel: String = "Peer",
    selfLabel: String = "Me",
    private val devices: ManagedCallMediaDevices? = null,
) : CallEngine {
    override val mediaDevices: CallMediaDevices? get() = devices
    private val _events = Channel<MediaEvent>(Channel.UNLIMITED)
    override val events: Flow<MediaEvent> = _events.receiveAsFlow()

    private val remoteSource = FakeVideo(peerLabel.dotsToSpaces().initials())
    private val localSource = FakeVideo(selfLabel.dotsToSpaces().initials())
    private val _remoteVideo = MutableStateFlow<FakeVideo?>(null)
    private val _localVideo = MutableStateFlow<FakeVideo?>(null)
    override val remoteVideo: StateFlow<FakeVideo?> = _remoteVideo.asStateFlow()
    override val localVideo: StateFlow<FakeVideo?> = _localVideo.asStateFlow()

    var muted = false
        private set
    var route = AudioRoute.Earpiece
        private set
    var frontCamera = true
        private set
    var closed = false
        private set
    val addedIce = mutableListOf<String>()

    private var scriptJob: Job? = null
    private val mediaState = MediaStateLink("FakeCallEngine") { _events.trySend(it) }

    override suspend fun createOffer(video: Boolean): String {
        devices?.applyPreferences()
        script.failOnCreateOffer?.let { throw it }
        _localVideo.value = if (video) localSource else null
        mediaState.setCamera(video)
        script.localCandidates.forEach { _events.send(MediaEvent.LocalIce(it, "0", 0)) }
        return FAKE_OFFER_SDP
    }

    override suspend fun answer(offerSdp: String, video: Boolean): String {
        devices?.applyPreferences()
        _localVideo.value = if (video) localSource else null
        mediaState.setCamera(video)
        _remoteVideo.value = null
        startScript()
        return "fake-answer"
    }

    override suspend fun applyRemote(answerSdp: String) = startScript()

    var restarts = 0
        private set

    override suspend fun restartIce(): String {
        restarts++
        return "fake-restart-offer"
    }

    override suspend fun answerRestart(offerSdp: String): String {
        restarts++
        return "fake-restart-answer"
    }

    override suspend fun addIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        addedIce += candidate
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        mediaState.setMic(!muted)
    }

    override suspend fun addVideo(): String {
        _localVideo.value = localSource
        mediaState.setCamera(true)
        return "fake-video-offer"
    }

    override fun setVideo(on: Boolean) {
        if (on) _localVideo.value = localSource
        if (_localVideo.value != null) mediaState.setCamera(on)
    }

    override fun switchCamera() { frontCamera = !frontCamera }

    override fun setAudioRoute(route: AudioRoute) { this.route = route }

    override fun close() {
        closed = true
        mediaState.closed()
        scriptJob?.cancel()
        _localVideo.value = null
        _remoteVideo.value = null
    }

    fun simulateDrop() { _events.trySend(MediaEvent.Disconnected) }

    fun simulateRecover() { _events.trySend(MediaEvent.Connected) }

    fun simulateFail(reason: FailReason = FailReason.NoRoute) { _events.trySend(MediaEvent.Failed(reason)) }

    fun simulateCameraInterrupted(interrupted: Boolean) = mediaState.cameraInterrupted(interrupted, "simulated")

    fun simulateRemoteVideo(on: Boolean) {
        _remoteVideo.value = if (on) remoteSource else null
        _events.trySend(MediaEvent.RemoteVideoChanged(on))
    }

    private fun startScript() {
        if (scriptJob != null) return
        scriptJob = scope.launch {
            delay(script.connectAfterMs)
            if (script.failConnect) return@launch simulateFail()
            _events.send(MediaEvent.Connected)
            if (script.remoteVideoOnConnect) simulateRemoteVideo(true)
            val drop = script.dropAtMs
            val videoOff = script.remoteVideoOffAtMs
            if (videoOff != null) launch { delay(videoOff); simulateRemoteVideo(false) }
            if (drop != null) {
                delay(drop)
                simulateDrop()
                script.recoverAfterDropMs?.let { delay(it); simulateRecover() }
            }
        }
    }

    companion object {
        const val FAKE_CANDIDATE = "candidate:1 1 udp 2122260223 192.0.2.1 50000 typ host"
        const val FAKE_OFFER_SDP = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\n"

        private fun String.dotsToSpaces() = replace('.', ' ')

        /** Opens the in-call media-state channel between two fakes, as the data channel does once DTLS is up. */
        fun connectMediaState(a: FakeCallEngine, b: FakeCallEngine) {
            a.mediaState.opened { b.mediaState.received(it); true }
            b.mediaState.opened { a.mediaState.received(it); true }
        }
    }
}
