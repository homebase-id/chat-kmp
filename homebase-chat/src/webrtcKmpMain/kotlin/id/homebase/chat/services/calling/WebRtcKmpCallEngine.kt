package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.DataChannel
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
import com.shepeliev.webrtckmp.RtpReceiver
import com.shepeliev.webrtckmp.RtpSender
import com.shepeliev.webrtckmp.RtpTransceiverDirection
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

private const val MAX_CAMERA_RECOVERIES = 3

/** [receiver] is set for remote video: iOS PiP needs the native RTCVideoTrack, which only the receiver exposes. */
class KmpVideoHandle(
    val track: VideoTrack,
    val mirrored: Boolean,
    val local: Boolean = false,
    val receiver: RtpReceiver? = null,
) : VideoHandle {
    override val frameSize = MutableStateFlow<Pair<Int, Int>?>(null)
}

/** Android and iOS engine over webrtc-kmp. Platform audio routing is delegated to [CallAudioRouter]. */
@OptIn(ExperimentalAtomicApi::class)
class WebRtcKmpCallEngine(stunUrls: List<String>) : CallEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val router = CallAudioRouter()

    private val _events = Channel<MediaEvent>(Channel.UNLIMITED)
    override val events: Flow<MediaEvent> = _events.receiveAsFlow()

    private val _local = MutableStateFlow<VideoHandle?>(null)
    private val _remote = MutableStateFlow<VideoHandle?>(null)
    override val localVideo: StateFlow<VideoHandle?> = _local.asStateFlow()
    override val remoteVideo: StateFlow<VideoHandle?> = _remote.asStateFlow()

    init {
        configureWebRtc()
    }

    // Every URL as given by the device's own server; nothing is derived or added.
    private val peer = PeerConnection(
        RtcConfiguration(iceServers = if (stunUrls.isEmpty()) emptyList() else listOf(IceServer(urls = stunUrls))),
    )

    private var stream: MediaStream? = null
    private var audioTrack: MediaStreamTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null

    @Volatile
    private var videoWanted = true

    // A camera opened after setup (turned on mid-call or recovered) lives in its own stream.
    private var cameraStream: MediaStream? = null
    private val closed = AtomicBoolean(false)
    private val mapper = PeerEventMapper { _events.trySend(it) }
    private val mediaState = MediaStateLink("WebRtcKmp") { _events.trySend(it) }
    private var stateChannel: DataChannel? = null
    private val stopPipelineWatch = watchVideoPipeline { interrupted, detail -> mediaState.cameraInterrupted(interrupted, detail) }
    private val senderParams = SenderParams { state -> videoSender?.let { applyVideoSender(it, state) } }
    private val sendLadder = SendResolutionLadder(
        applyFps = { fps -> senderParams.update { copy(maxFramerate = fps) } },
        apply = { scale -> senderParams.update { copy(scaleDownBy = scale) } },
    )
    private val stopPressureWatch = observeDevicePressure { pressure ->
        sendLadder.setPressure(pressure)
        Logger.i("WebRtcKmp") { pressure.describe() }
    }

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
                _remote.value = KmpVideoHandle(track, mirrored = false, receiver = event.receiver)
            }
        }
        scope.launch { logVideoStats() }
        scope.launch {
            while (!closed.load()) {
                delay(MEDIA_STATE_RESEND_MS)
                mediaState.resend()
            }
        }
    }

    private suspend fun logVideoStats() {
        var previous = emptyList<StatsEntry>()
        while (!closed.load()) {
            delay(5_000)
            val stats = runCatching { peer.getStats()?.stats?.values }.getOrNull() ?: continue
            val entries = stats.map { StatsEntry(it.type, it.members, it.id) }
            videoStatsLines(entries, localTrack = videoTrack?.state?.value?.toString(), previous = previous)
                .forEach { line -> Logger.i("WebRtcKmp") { line } }
            sendLadder.onStats(entries)?.let { line -> Logger.i("WebRtcKmp") { line } }
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

    private suspend fun addLocalMedia(video: Boolean, offering: Boolean) {
        if (closed.load()) throw CallEngineException(FailReason.Other, "engine closed")
        if (stream != null) return
        _events.trySend(MediaEvent.RouteChanged(router.start(video) { _events.trySend(MediaEvent.RouteChanged(it)) }))
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
                MediaStreamTrackKind.Video -> (track as? VideoTrack)?.let { attachCamera(it, sender) }
            }
        }
        if (offering && videoSender == null) {
            peer.addIdleVideoTransceiver()
            videoSender = peer.getTransceivers().lastOrNull { it.isVideo }?.sender
        }
        mediaState.setCamera(videoTrack != null)
        openStateChannel()
    }

    private fun attachCamera(track: VideoTrack, sender: RtpSender) {
        videoTrack = track
        videoSender = sender
        senderParams.update {
            copy(
                maxBitrateBps = SendVideoCap.MAX_BITRATE_BPS,
                minBitrateBps = SendVideoCap.MIN_BITRATE_BPS,
                maxFramerate = sendLadder.fps,
            )
        }
        publishLocal(track)
        Logger.i("WebRtcKmp") { "local video settings=${track.settings}" }
        watchCapturer(track, MAX_CAMERA_RECOVERIES)
    }

    // Answering send-receive lets either side start a camera later without renegotiating.
    private fun adoptOfferedVideo() {
        if (videoSender != null) return
        val transceiver = peer.getTransceivers().firstOrNull { it.isVideo } ?: return
        transceiver.direction = RtpTransceiverDirection.SendRecv
        videoSender = transceiver.sender
    }

    override suspend fun addVideo(): Boolean {
        if (closed.load()) return false
        if (videoTrack != null) return true
        val sender = videoSender ?: return false.also { Logger.w("WebRtcKmp") { "no video transceiver was negotiated, the camera stays off" } }
        val (track, media) = openCameraOnto(sender, enabled = true, "mid-call camera") ?: return false
        cameraStream = media
        videoWanted = true
        attachCamera(track, sender)
        mediaState.setCamera(true)
        return true
    }

    // Null after logging and releasing the camera when it cannot open, the engine closed meanwhile, or the swap fails.
    private suspend fun openCameraOnto(sender: RtpSender, enabled: Boolean, purpose: String): Pair<VideoTrack, MediaStream?>? {
        var media: MediaStream? = null
        fun discard(): Nothing? {
            runCatching { media?.tracks?.forEach { it.stop() } }
            runCatching { media?.release() }
            return null
        }
        val track = runCatching {
            MediaDevices.getUserMedia { requestCamera() }.also { media = it }
                .tracks.filterIsInstance<VideoTrack>().firstOrNull() ?: error("no video track")
        }.onFailure {
            if (it is CancellationException) throw it
            Logger.w(it, "WebRtcKmp") { "$purpose failed, video stays off" }
        }.getOrNull() ?: return discard()
        if (closed.load()) return discard()
        track.enabled = enabled
        runCatching { sender.replaceTrack(track) }.onFailure {
            if (it is CancellationException) throw it
            Logger.w(it, "WebRtcKmp") { "$purpose could not replace the sender track, video stays off" }
            return discard()
        }
        return track to media
    }

    // webrtc-kmp stops the track itself on a camera error or freeze; nothing else reports it.
    private fun watchCapturer(track: VideoTrack, recoveriesLeft: Int) {
        scope.launch {
            track.state.first { state -> state is MediaStreamTrackState.Ended }
            if (closed.load()) return@launch
            Logger.w("WebRtcKmp") { "local video track ended by capturer device=${track.settings.deviceId} recoveriesLeft=$recoveriesLeft" }
            mediaState.capturerEnded()
            if (recoveriesLeft > 0) recoverCamera(recoveriesLeft - 1)
        }
    }

    private suspend fun recoverCamera(recoveriesLeft: Int) {
        val sender = videoSender ?: return
        val (fresh, media) = openCameraOnto(sender, enabled = videoWanted, "camera recovery") ?: return
        runCatching { cameraStream?.release() }
        cameraStream = media
        videoTrack = fresh
        publishLocal(fresh)
        Logger.i("WebRtcKmp") { "camera recovered settings=${fresh.settings}" }
        watchCapturer(fresh, recoveriesLeft)
        mediaState.capturerRestored()
    }

    // Created after the tracks so the audio and video m-lines keep their indexes; an older peer never opens it.
    private fun openStateChannel() {
        val channel = peer.createDataChannel(
            label = MEDIA_STATE_CHANNEL_LABEL,
            id = MEDIA_STATE_CHANNEL_ID,
            ordered = true,
            negotiated = true,
        ) ?: return Logger.w("WebRtcKmp") { "media-state data channel unavailable" }
        stateChannel = channel
        scope.launch(start = CoroutineStart.UNDISPATCHED) { channel.onOpen.collect { mediaState.opened(channel::send) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { channel.onClose.collect { mediaState.closed() } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { channel.onMessage.collect { mediaState.received(it) } }
    }

    private fun Exception.isPermissionError() =
        this::class.simpleName.orEmpty().contains("Permission", ignoreCase = true)

    // offerToReceiveVideo = false would strip the receive direction from the idle video transceiver.
    override suspend fun createOffer(video: Boolean): String {
        addLocalMedia(video, offering = true)
        return offer(OfferAnswerOptions(offerToReceiveAudio = true))
    }

    override suspend fun answer(offerSdp: String, video: Boolean): String {
        addLocalMedia(video, offering = false)
        peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Offer, offerSdp))
        adoptOfferedVideo()
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
        peer.setLocalDescription(created)
        return created.sdp
    }

    override fun setMuted(muted: Boolean) {
        audioTrack?.enabled = !muted
        mediaState.setMic(!muted)
    }

    // Disabling webrtc-kmp's local video track also stops its capturer, so the camera is released, not just blanked.
    override fun setVideo(on: Boolean) {
        val track = videoTrack ?: return
        videoWanted = on
        track.enabled = on
        mediaState.setCamera(on)
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
        stopPressureWatch()
        val start = TimeSource.Monotonic.markNow()
        mediaState.closed()
        runCatching { stateChannel?.close() }
        runCatching { stream?.tracks?.forEach { it.stop() } }
        val tracks = start.elapsedNow()
        runCatching { stream?.release() }
        runCatching { cameraStream?.tracks?.forEach { it.stop() } }
        runCatching { cameraStream?.release() }
        val released = start.elapsedNow()
        runCatching { peer.close() }
        val peerClosed = start.elapsedNow()
        router.stop()
        _local.value = null
        _remote.value = null
        Logger.i("WebRtcKmp") {
            "engine closed in ${start.elapsedNow().inWholeMilliseconds} ms: tracks=${tracks.inWholeMilliseconds} " +
                "release=${(released - tracks).inWholeMilliseconds} peer=${(peerClosed - released).inWholeMilliseconds}"
        }
    }
}
