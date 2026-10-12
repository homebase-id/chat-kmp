package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack
import java.util.Timer
import kotlin.concurrent.timerTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Desktop engine over webrtc-java. Pass [audioDevice] (e.g. a headless module) to avoid touching real hardware. */
class WebRtcJavaCallEngine(
    stunUrls: List<String>,
    audioDevice: AudioDeviceModuleBase? = null,
) : CallEngine {
    private val _events = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<MediaEvent> = _events.asSharedFlow()

    private val _local = MutableStateFlow<VideoHandle?>(null)
    private val _remote = MutableStateFlow<VideoHandle?>(null)
    override val localVideo: StateFlow<VideoHandle?> = _local.asStateFlow()
    override val remoteVideo: StateFlow<VideoHandle?> = _remote.asStateFlow()

    private val factory = if (audioDevice != null) PeerConnectionFactory(audioDevice) else PeerConnectionFactory()
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoDeviceSource? = null
    private var localFrames: JvmVideoHandle? = null
    private var remoteFrames: JvmVideoHandle? = null
    private var remoteTrack: VideoTrack? = null
    private var cameraIndex = 0
    private val mapper = PeerEventMapper { _events.tryEmit(it) }

    // Native callbacks arrive on WebRTC threads; the lock keeps close() from disposing the connection under a live call.
    private val lock = Any()
    @Volatile private var closed = false

    private val peer: RTCPeerConnection = factory.createPeerConnection(
        RTCConfiguration().apply {
            // Every URL as given by the device's own server; nothing is derived or added.
            if (stunUrls.isNotEmpty()) iceServers = listOf(RTCIceServer().apply { urls = stunUrls })
        },
        object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                if (!closed) mapper.localIce(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                if (!closed) mapper.linkState(state.toLinkState())
            }

            override fun onTrack(transceiver: RTCRtpTransceiver) {
                val track = transceiver.receiver.track as? VideoTrack ?: return
                synchronized(lock) {
                    if (closed) return
                    val handle = JvmVideoHandle().also { remoteFrames = it }
                    track.addSink(handle.sink)
                    remoteTrack = track
                    _remote.value = handle
                }
                mapper.remoteVideo(true)
            }
        },
    ) ?: error("webrtc-java returned no peer connection")

    private val statsTimer = Timer("call-stats", true).apply {
        schedule(timerTask { logVideoStats() }, STATS_PERIOD_MS, STATS_PERIOD_MS)
    }

    private fun logVideoStats() {
        synchronized(lock) {
            if (closed) return
            peer.getStats { report ->
                val entries = report.stats.values.map { StatsEntry(it.type.name.lowercase().replace('_', '-'), it.attributes) }
                videoStatsLines(entries, localTrack = videoTrack?.let { if (it.isEnabled) "enabled" else "disabled" })
                    .forEach { line -> Logger.i("WebRtcJava") { line } }
            }
        }
    }

    private fun RTCPeerConnectionState.toLinkState() = when (this) {
        RTCPeerConnectionState.NEW -> PeerLinkState.New
        RTCPeerConnectionState.CONNECTING -> PeerLinkState.Connecting
        RTCPeerConnectionState.CONNECTED -> PeerLinkState.Connected
        RTCPeerConnectionState.DISCONNECTED -> PeerLinkState.Disconnected
        RTCPeerConnectionState.FAILED -> PeerLinkState.Failed
        RTCPeerConnectionState.CLOSED -> PeerLinkState.Closed
    }

    private fun checkOpen() {
        if (closed) throw CallEngineException(FailReason.Other, "engine closed")
    }

    private fun addLocalMedia(video: Boolean) = synchronized(lock) {
        checkOpen()
        if (audioTrack == null) {
            val source = factory.createAudioSource(AudioOptions().apply {
                echoCancellation = true
                autoGainControl = true
                noiseSuppression = true
            })
            audioTrack = factory.createAudioTrack("audio0", source).also { peer.addTrack(it, listOf(STREAM_ID)) }
        }
        if (video && videoTrack == null) startCamera()
    }

    private fun startCamera() {
        try {
            val devices = dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices()
            val device = devices.firstOrNull() ?: run {
                Logger.w("WebRtcJava") { "no camera found, continuing audio-only" }
                return
            }
            Logger.i("WebRtcJava") { "camera ${device.name} of ${devices.map { it.name }}" }
            val source = VideoDeviceSource().apply {
                setVideoCaptureDevice(device)
                start()
            }
            videoSource = source
            val track = factory.createVideoTrack("video0", source)
            // The Mac camera faces the user, so its preview is mirrored; the transmitted frames are untouched.
            val handle = JvmVideoHandle.localPreview(DESKTOP_CAMERA_FACING).also { localFrames = it }
            track.addSink(handle.sink)
            peer.addTrack(track, listOf(STREAM_ID))
            videoTrack = track
            _local.value = handle
        } catch (e: Exception) {
            Logger.w(e, "WebRtcJava") { "camera unavailable, continuing audio-only" }
        }
    }

    override suspend fun createOffer(video: Boolean): String {
        addLocalMedia(video)
        return offer(RTCOfferOptions())
    }

    override suspend fun answer(offerSdp: String, video: Boolean): String {
        addLocalMedia(video)
        setRemote(RTCSessionDescription(RTCSdpType.OFFER, offerSdp))
        return createAnswer()
    }

    override suspend fun applyRemote(answerSdp: String) = setRemote(RTCSessionDescription(RTCSdpType.ANSWER, answerSdp))

    override suspend fun restartIce(): String = offer(RTCOfferOptions().apply { iceRestart = true })

    override suspend fun answerRestart(offerSdp: String): String {
        setRemote(RTCSessionDescription(RTCSdpType.OFFER, offerSdp))
        return createAnswer()
    }

    override suspend fun addIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        synchronized(lock) {
            if (closed) return
            peer.addIceCandidate(RTCIceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate))
        }
    }

    private suspend fun offer(options: RTCOfferOptions): String {
        checkOpen()
        val description = suspendCancellableCoroutine { cont ->
            peer.createOffer(options, object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) = cont.resume(description)
                override fun onFailure(error: String) = cont.resumeWithException(CallEngineException(FailReason.Other, error))
            })
        }
        setLocal(description)
        return description.sdp
    }

    private suspend fun createAnswer(): String {
        checkOpen()
        val description = suspendCancellableCoroutine { cont ->
            peer.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) = cont.resume(description)
                override fun onFailure(error: String) = cont.resumeWithException(CallEngineException(FailReason.Other, error))
            })
        }
        setLocal(description)
        return description.sdp
    }

    private suspend fun setLocal(description: RTCSessionDescription) = suspendCancellableCoroutine { cont ->
        checkOpen()
        peer.setLocalDescription(description, setObserver(cont))
    }

    private suspend fun setRemote(description: RTCSessionDescription) = suspendCancellableCoroutine { cont ->
        checkOpen()
        peer.setRemoteDescription(description, setObserver(cont))
    }

    private fun setObserver(cont: kotlinx.coroutines.CancellableContinuation<Unit>) = object : SetSessionDescriptionObserver {
        override fun onSuccess() = cont.resume(Unit)
        override fun onFailure(error: String) = cont.resumeWithException(CallEngineException(FailReason.Other, error))
    }

    override fun setMuted(muted: Boolean) {
        synchronized(lock) { if (!closed) audioTrack?.isEnabled = !muted }
    }

    override fun setVideo(on: Boolean) {
        synchronized(lock) { if (!closed) videoTrack?.isEnabled = on }
    }

    override fun switchCamera() {
        synchronized(lock) {
            if (closed) return
            val devices = dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices()
            val source = videoSource ?: return
            if (devices.size < 2) return
            cameraIndex = (cameraIndex + 1) % devices.size
            source.stop()
            source.setVideoCaptureDevice(devices[cameraIndex])
            source.start()
        }
    }

    override fun setAudioRoute(route: AudioRoute) = Unit

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            statsTimer.cancel()
            localFrames?.let { sink -> videoTrack?.let { runCatching { it.removeSink(sink.sink) } } }
            remoteFrames?.let { sink -> remoteTrack?.let { runCatching { it.removeSink(sink.sink) } } }
            runCatching { peer.close() }
            runCatching { videoSource?.stop() }
            runCatching { audioTrack?.dispose() }
            runCatching { videoTrack?.dispose() }
            runCatching { videoSource?.dispose() }
            localFrames?.close()
            remoteFrames?.close()
            _local.value = null
            _remote.value = null
            runCatching { factory.dispose() }
        }
    }

    private companion object {
        const val STREAM_ID = "homebase-call"
        const val STATS_PERIOD_MS = 5_000L
        val DESKTOP_CAMERA_FACING = CameraFacing.Front
    }
}
