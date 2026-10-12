package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.FacingMode
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.MediaStreamTrack
import com.shepeliev.webrtckmp.MediaStreamTrackKind
import com.shepeliev.webrtckmp.MediaStreamTrackState
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.PeerConnectionState
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.RtpSender
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.VideoTrack
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class KmpVideoHandle(val track: VideoTrack, val mirrored: Boolean, val local: Boolean = false) : VideoHandle

/** Android and iOS engine over webrtc-kmp. Platform audio routing is delegated to [CallAudioRouter]. */
@OptIn(ExperimentalAtomicApi::class)
class WebRtcKmpCallEngine(stunUrls: List<String>) : CallEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val router = CallAudioRouter()

    private val _events = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<MediaEvent> = _events.asSharedFlow()

    private val _local = MutableStateFlow<VideoHandle?>(null)
    private val _remote = MutableStateFlow<VideoHandle?>(null)
    override val localVideo: StateFlow<VideoHandle?> = _local.asStateFlow()
    override val remoteVideo: StateFlow<VideoHandle?> = _remote.asStateFlow()

    init {
        capLocalVideoSource()
    }

    // Every URL as given by the device's own server; nothing is derived or added.
    private val peer = PeerConnection(
        RtcConfiguration(iceServers = if (stunUrls.isEmpty()) emptyList() else listOf(IceServer(urls = stunUrls))),
    )

    private var stream: MediaStream? = null
    private var audioTrack: MediaStreamTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private val closed = AtomicBoolean(false)
    private val mapper = PeerEventMapper { _events.tryEmit(it) }
    private val stopPipelineWatch = watchVideoPipeline()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.onIceCandidate.collect { mapper.localIce(it.candidate, it.sdpMid, it.sdpMLineIndex) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.onConnectionStateChange.collect { mapper.linkState(it.toLinkState()) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.onTrack.collect { event ->
                val track = event.track as? VideoTrack ?: return@collect
                _remote.value = KmpVideoHandle(track, mirrored = false)
                mapper.remoteVideo(true)
            }
        }
        scope.launch { logVideoStats() }
    }

    private suspend fun logVideoStats() {
        var previous = emptyList<StatsEntry>()
        while (!closed.load()) {
            delay(5_000)
            val stats = runCatching { peer.getStats()?.stats?.values }.getOrNull() ?: continue
            val entries = stats.map { StatsEntry(it.type, it.members) }
            videoStatsLines(entries, localTrack = videoTrack?.state?.value?.toString(), previous = previous)
                .forEach { line -> Logger.i("WebRtcKmp") { line } }
            previous = entries
        }
    }

    private fun PeerConnectionState.toLinkState() = when (this) {
        PeerConnectionState.New -> PeerLinkState.New
        PeerConnectionState.Connecting -> PeerLinkState.Connecting
        PeerConnectionState.Connected -> PeerLinkState.Connected
        PeerConnectionState.Disconnected -> PeerLinkState.Disconnected
        PeerConnectionState.Failed -> PeerLinkState.Failed
        PeerConnectionState.Closed -> PeerLinkState.Closed
    }

    private fun localFacing(track: VideoTrack) =
        if (track.settings.facingMode == FacingMode.Environment) CameraFacing.Back else CameraFacing.Front

    private fun publishLocal(track: VideoTrack) {
        _local.value = KmpVideoHandle(track, mirrored = localFacing(track).mirrorsLocalPreview, local = true)
    }

    private suspend fun addLocalMedia(video: Boolean) {
        if (closed.load()) throw CallEngineException(FailReason.Other, "engine closed")
        if (stream != null) return
        _events.tryEmit(MediaEvent.RouteChanged(router.start(video)))
        val media = try {
            MediaDevices.getUserMedia {
                audio(true)
                if (video) requestCamera()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CallEngineException(if (e.isPermissionError()) FailReason.Permission else FailReason.Other, e.message)
        }
        if (closed.load()) {
            runCatching { media.tracks.forEach { it.stop() } }
            runCatching { media.release() }
            throw CallEngineException(FailReason.Other, "engine closed")
        }
        stream = media
        for (track in media.tracks) {
            val sender = peer.addTrack(track, media)
            when (track.kind) {
                MediaStreamTrackKind.Audio -> audioTrack = track
                MediaStreamTrackKind.Video -> (track as? VideoTrack)?.let {
                    videoTrack = it
                    videoSender = sender
                    configureVideoSender(sender)
                    publishLocal(it)
                    Logger.i("WebRtcKmp") { "local video settings=${it.settings}" }
                    // webrtc-kmp stops the track itself on a camera error or freeze; nothing else reports it.
                    scope.launch {
                        it.state.first { state -> state is MediaStreamTrackState.Ended }
                        if (!closed.load()) Logger.w("WebRtcKmp") { "local video track ended by capturer device=${it.settings.deviceId}" }
                    }
                }
            }
        }
    }

    private fun Exception.isPermissionError() =
        this::class.simpleName.orEmpty().contains("Permission", ignoreCase = true)

    override suspend fun createOffer(video: Boolean): String {
        addLocalMedia(video)
        return offer(OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = video))
    }

    override suspend fun answer(offerSdp: String, video: Boolean): String {
        addLocalMedia(video)
        peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Offer, offerSdp))
        return createAnswer().also { logVideoSender() }
    }

    override suspend fun applyRemote(answerSdp: String) {
        peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Answer, answerSdp))
        logVideoSender()
    }

    private fun logVideoSender() {
        val sender = videoSender ?: return
        Logger.i("WebRtcKmp") { "video sender ${describeVideoSender(sender)}" }
    }

    override suspend fun restartIce(): String = offer(OfferAnswerOptions(iceRestart = true))

    override suspend fun answerRestart(offerSdp: String): String {
        peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Offer, offerSdp))
        return createAnswer()
    }

    override suspend fun addIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        if (closed.load()) return
        peer.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate))
    }

    private suspend fun offer(options: OfferAnswerOptions): String = setLocal(peer.createOffer(options))

    private suspend fun createAnswer(): String = setLocal(peer.createAnswer(OfferAnswerOptions()))

    private suspend fun setLocal(created: SessionDescription): String {
        val description = SessionDescription(created.type, withH264SendLevel(created.sdp))
        peer.setLocalDescription(description)
        return description.sdp
    }

    override fun setMuted(muted: Boolean) {
        audioTrack?.enabled = !muted
    }

    override fun setVideo(on: Boolean) {
        videoTrack?.enabled = on
    }

    override fun switchCamera() {
        val track = videoTrack ?: return
        scope.launch {
            val current = track.settings.deviceId
            // Cycling (null) on Android walks into auxiliary lenses that may deliver no frames; a freeze then ends the track.
            val target = flipTarget(current, platformCameras())
            Logger.i("WebRtcKmp") { "switchCamera from=$current to=${target ?: "next"}" }
            runCatching { track.switchCamera(target) }
                .onSuccess { if (!closed.load()) publishLocal(track) }
                .onFailure { Logger.w(it, "WebRtcKmp") { "switchCamera failed" } }
        }
    }

    override fun setAudioRoute(route: AudioRoute) {
        if (!closed.load()) router.setRoute(route)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        stopPipelineWatch()
        runCatching { stream?.tracks?.forEach { it.stop() } }
        runCatching { stream?.release() }
        runCatching { peer.close() }
        router.stop()
        _local.value = null
        _remote.value = null
    }
}
