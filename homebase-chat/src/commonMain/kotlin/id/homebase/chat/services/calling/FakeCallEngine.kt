package id.homebase.chat.services.calling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import id.homebase.core.util.initials

data class FakeVideo(val argb: Long, val initials: String) : VideoHandle

data class FakeCallScript(
    val connectAfterMs: Long = 1_000,
    val dropAtMs: Long? = null,
    val recoverAfterDropMs: Long? = null,
    val remoteVideoOffAtMs: Long? = null,
    val failConnect: Boolean = false,
    val remoteVideoOnConnect: Boolean = false,
    val failOnCreateOffer: Throwable? = null,
)

class FakeCallEngine(
    private val scope: CoroutineScope,
    private val script: FakeCallScript = FakeCallScript(),
    peerLabel: String = "Peer",
    selfLabel: String = "Me",
) : CallEngine {
    private val _events = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<MediaEvent> = _events.asSharedFlow()

    private val remoteSource = FakeVideo(colorFor(peerLabel), peerLabel.dotsToSpaces().initials())
    private val localSource = FakeVideo(colorFor(selfLabel), selfLabel.dotsToSpaces().initials())
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

    override suspend fun createOffer(video: Boolean): String {
        script.failOnCreateOffer?.let { throw it }
        _localVideo.value = if (video) localSource else null
        _events.emit(MediaEvent.LocalIce("fake-candidate-local", "0", 0))
        return "fake-offer"
    }

    override suspend fun answer(offerSdp: String, video: Boolean): String {
        _localVideo.value = if (video) localSource else null
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

    override fun setMuted(muted: Boolean) { this.muted = muted }

    override fun setVideo(on: Boolean) { if (on) _localVideo.value = localSource }

    override fun switchCamera() { frontCamera = !frontCamera }

    override fun setAudioRoute(route: AudioRoute) { this.route = route }

    override fun close() {
        closed = true
        scriptJob?.cancel()
        _localVideo.value = null
        _remoteVideo.value = null
    }

    fun simulateDrop() { _events.tryEmit(MediaEvent.Disconnected) }

    fun simulateRecover() { _events.tryEmit(MediaEvent.Connected) }

    fun simulateFail(reason: FailReason = FailReason.NoRoute) { _events.tryEmit(MediaEvent.Failed(reason)) }

    fun simulateRemoteVideo(on: Boolean) {
        _remoteVideo.value = if (on) remoteSource else null
        _events.tryEmit(MediaEvent.RemoteVideoChanged(on))
    }

    private fun startScript() {
        if (scriptJob != null) return
        scriptJob = scope.launch {
            delay(script.connectAfterMs)
            if (script.failConnect) return@launch simulateFail()
            _events.emit(MediaEvent.Connected)
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

    private companion object {
        val palette = longArrayOf(0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFF8E24AA, 0xFF2E7D32, 0xFFC62828)

        fun colorFor(label: String) = palette[(label.hashCode() and Int.MAX_VALUE) % palette.size]

        fun String.dotsToSpaces() = replace('.', ' ')
    }
}
