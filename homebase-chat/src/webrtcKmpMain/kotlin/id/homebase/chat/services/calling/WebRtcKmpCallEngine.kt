package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.MediaStreamTrack
import com.shepeliev.webrtckmp.MediaStreamTrackKind
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.PeerConnectionState
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.VideoTrack
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KmpVideoHandle(val track: VideoTrack, val mirrored: Boolean) : VideoHandle

/** Android and iOS engine over webrtc-kmp. Platform audio routing is delegated to [CallAudioRouter]. */
class WebRtcKmpCallEngine(stunUrls: List<String>) : CallEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val router = CallAudioRouter()

    private val _events = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<MediaEvent> = _events.asSharedFlow()

    private val _local = MutableStateFlow<VideoHandle?>(null)
    private val _remote = MutableStateFlow<VideoHandle?>(null)
    override val localVideo: StateFlow<VideoHandle?> = _local.asStateFlow()
    override val remoteVideo: StateFlow<VideoHandle?> = _remote.asStateFlow()

    // Every URL as given by the device's own server; nothing is derived or added.
    private val peer = PeerConnection(
        RtcConfiguration(iceServers = if (stunUrls.isEmpty()) emptyList() else listOf(IceServer(urls = stunUrls))),
    )

    private var stream: MediaStream? = null
    private var audioTrack: MediaStreamTrack? = null
    private var videoTrack: VideoTrack? = null
    private var frontCamera = true
    private var everConnected = false
    private var closed = false

    init {
        router.start()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.onIceCandidate.collect { _events.tryEmit(MediaEvent.LocalIce(it.candidate, it.sdpMid, it.sdpMLineIndex)) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.onConnectionStateChange.collect { state ->
                when (state) {
                    PeerConnectionState.Connected -> {
                        everConnected = true
                        _events.tryEmit(MediaEvent.Connected)
                    }
                    PeerConnectionState.Disconnected -> _events.tryEmit(MediaEvent.Disconnected)
                    PeerConnectionState.Failed ->
                        _events.tryEmit(if (everConnected) MediaEvent.Disconnected else MediaEvent.Failed(FailReason.NoRoute))
                    else -> Unit
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.onTrack.collect { event ->
                val track = event.track as? VideoTrack ?: return@collect
                _remote.value = KmpVideoHandle(track, mirrored = false)
                _events.tryEmit(MediaEvent.RemoteVideoChanged(true))
            }
        }
    }

    private suspend fun addLocalMedia(video: Boolean) {
        if (stream != null) return
        val media = try {
            MediaDevices.getUserMedia(audio = true, video = video)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CallEngineException(if (e.isPermissionError()) FailReason.Permission else FailReason.Other, e.message)
        }
        stream = media
        for (track in media.tracks) {
            peer.addTrack(track, media)
            when (track.kind) {
                MediaStreamTrackKind.Audio -> audioTrack = track
                MediaStreamTrackKind.Video -> (track as? VideoTrack)?.let {
                    videoTrack = it
                    _local.value = KmpVideoHandle(it, mirrored = true)
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
        return createAnswer()
    }

    override suspend fun applyRemote(answerSdp: String) =
        peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Answer, answerSdp))

    override suspend fun restartIce(): String = offer(OfferAnswerOptions(iceRestart = true))

    override suspend fun answerRestart(offerSdp: String): String {
        peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Offer, offerSdp))
        return createAnswer()
    }

    override suspend fun addIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        peer.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate))
    }

    private suspend fun offer(options: OfferAnswerOptions): String {
        val description = peer.createOffer(options)
        peer.setLocalDescription(description)
        return description.sdp
    }

    private suspend fun createAnswer(): String {
        val description = peer.createAnswer(OfferAnswerOptions())
        peer.setLocalDescription(description)
        return description.sdp
    }

    override fun setMuted(muted: Boolean) {
        audioTrack?.enabled = !muted
    }

    override fun setVideo(on: Boolean) {
        val track = videoTrack ?: return
        track.enabled = on
    }

    override fun switchCamera() {
        val track = videoTrack ?: return
        scope.launch {
            runCatching { track.switchCamera() }
                .onSuccess { frontCamera = !frontCamera }
                .onFailure { Logger.w(it, "WebRtcKmp") { "switchCamera failed" } }
        }
    }

    override fun setAudioRoute(route: AudioRoute) = router.setRoute(route)

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        runCatching { stream?.tracks?.forEach { it.stop() } }
        runCatching { stream?.release() }
        runCatching { peer.close() }
        router.stop()
        _local.value = null
        _remote.value = null
    }
}
