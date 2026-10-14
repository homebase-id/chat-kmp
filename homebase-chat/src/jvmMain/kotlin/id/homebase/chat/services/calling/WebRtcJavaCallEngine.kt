package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import co.touchlab.kermit.Logger
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.logging.LogSink
import dev.onvoid.webrtc.logging.Logging
import dev.onvoid.webrtc.media.Device
import dev.onvoid.webrtc.media.DeviceChangeListener
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.audio.AudioDevice
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDevice
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack
import java.nio.ByteBuffer
import java.util.Timer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timerTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Desktop engine over webrtc-java. Pass [audioDevice] (e.g. a headless module) to avoid touching real hardware. */
class WebRtcJavaCallEngine(
    stunUrls: List<String>,
    audioDevice: AudioDeviceModuleBase? = null,
    devicePreferences: CallDevicePreferences = InMemoryCallDevicePreferences(),
) : CallEngine {
    private val _events = Channel<MediaEvent>(Channel.UNLIMITED)
    override val events: Flow<MediaEvent> = _events.receiveAsFlow()

    private val _local = MutableStateFlow<VideoHandle?>(null)
    private val _remote = MutableStateFlow<VideoHandle?>(null)
    override val localVideo: StateFlow<VideoHandle?> = _local.asStateFlow()
    override val remoteVideo: StateFlow<VideoHandle?> = _remote.asStateFlow()

    init {
        nativeLogs
    }

    private val ownsAudioDevice = audioDevice == null
    private val adm: AudioDeviceModuleBase = audioDevice ?: AudioDeviceModule()
    private val factory = PeerConnectionFactory(adm)
    @Volatile private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RTCRtpSender? = null
    private val sendLadder = SendResolutionLadder { scale -> videoSender?.let { scaleVideoSender(it, scale) } }
    private var videoSource: VideoDeviceSource? = null
    private val capturedFrames = FrameCounter()
    private val cameraWatch = Timer("call-camera-watch", true)
    private var localFrames: JvmVideoHandle? = null
    private var remoteFrames: JvmVideoHandle? = null
    private var remoteTrack: VideoTrack? = null
    private val mapper = PeerEventMapper { _events.trySend(it) }
    private val mediaState = MediaStateLink("WebRtcJava") { _events.trySend(it) }
    private var stateChannel: RTCDataChannel? = null

    // Channel calls block on the signaling thread, so it never takes this lock: the open callback hands off to the timer.
    private val stateLock = Any()
    @Volatile private var cameraOn = true
    private var cameraTried = emptyList<String>()

    // Native callbacks arrive on WebRTC threads; the lock keeps close() from disposing the connection under a live call.
    internal val lock = Any()

    // Held by the signaling thread in onTrack, so never across a peer call: getStats and addIceCandidate block on that thread.
    private val remoteLock = Any()
    @Volatile private var closed = false

    // deviceLock serialises picker work and is always taken before [lock]; close() never takes it.
    private val deviceLock = Any()
    private val nativeDevices = mutableMapOf<Pair<MediaDeviceKind, String>, Device>()

    // getSignalLevel waits on the signaling thread, so the meter runs under its own lock, never [lock].
    private val levelLock = Any()
    private var levelStopped = false
    @Volatile private var live = false
    @Volatile private var micMuted = false

    private val deviceExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "call-devices").apply { isDaemon = true } }
    private val listenerRegistered = AtomicBoolean(false)
    private val refreshQueued = AtomicBoolean(false)
    private val deviceListener = object : DeviceChangeListener {
        override fun deviceConnected(device: Device) = refreshDevices()
        override fun deviceDisconnected(device: Device) = refreshDevices()
    }

    private val devices = ManagedCallMediaDevices(
        enumerate = ::enumerateDevices,
        apply = ::applyDevice,
        preferences = devicePreferences,
        serialize = { step -> synchronized(deviceLock) { step() } },
        onApplyFailed = { device, e -> Logger.w(e, "WebRtcJava") { "could not switch ${device.kind} to ${device.name}" } },
    )
    override val mediaDevices: CallMediaDevices get() = devices

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
                live = state == RTCPeerConnectionState.CONNECTED
                if (!closed) mapper.linkState(state.toLinkState())
            }

            override fun onTrack(transceiver: RTCRtpTransceiver) {
                val track = transceiver.receiver.track as? VideoTrack ?: return
                synchronized(remoteLock) {
                    if (closed) return
                    val handle = JvmVideoHandle().also { remoteFrames = it }
                    track.addSink(handle.sink)
                    remoteTrack = track
                    _remote.value = handle
                }
                mediaState.remoteTrackArrived()
            }
        },
    ) ?: error("webrtc-java returned no peer connection")

    private val statsTimer = Timer("call-stats", true).apply {
        schedule(timerTask { logVideoStats() }, STATS_PERIOD_MS, STATS_PERIOD_MS)
    }

    private val micLevelTimer = Timer("call-mic-level", true).apply {
        schedule(timerTask { pollMicLevel() }, MIC_LEVEL_PERIOD_MS, MIC_LEVEL_PERIOD_MS)
    }

    private val mediaStateTimer = Timer("call-media-state", true).apply {
        schedule(timerTask { mediaState.resend() }, MEDIA_STATE_RESEND_MS, MEDIA_STATE_RESEND_MS)
    }

    init {
        devices.refresh()
        // Hot-plug needs the platform device monitor (PulseAudio on Linux, which webrtc-java reports as java.lang.Error); without it the call still runs on the devices enumerated above.
        if (!closed) {
            runCatching { MediaDevices.addDeviceChangeListener(deviceListener) }
                .onSuccess { listenerRegistered.set(true) }
                .onFailure { Logger.w(it, "WebRtcJava") { "device hot-plug unavailable, the pickers will not follow device changes" } }
        }
    }

    @Volatile
    private var previousStats = emptyList<StatsEntry>()

    // A hot-plug fires a burst of callbacks; one enumeration covers them all.
    private fun refreshDevices() {
        if (closed || !refreshQueued.compareAndSet(false, true)) return
        runCatching {
            deviceExecutor.execute {
                refreshQueued.set(false)
                if (!closed) devices.refresh()
            }
        }.onFailure { refreshQueued.set(false) }
    }

    private fun pollMicLevel() {
        val level = synchronized(levelLock) {
            if (levelStopped) return
            val track = audioTrack
            if (!live || micMuted || track == null) 0f else normalizeMicLevel(track.signalLevel)
        }
        devices.setMicLevel(level)
    }

    private fun enumerateDevices(): List<MediaDevice> {
        nativeDevices.clear()
        val found = mutableListOf<MediaDevice>()
        fun add(kind: MediaDeviceKind, list: List<Device>, defaultId: String?) = list.forEach { device ->
            nativeDevices[kind to device.descriptor] = device
            found += MediaDevice(device.descriptor, device.name, kind, isDefault = device.descriptor == defaultId)
        }
        runCatching {
            add(MediaDeviceKind.Mic, MediaDevices.getAudioCaptureDevices(), runCatching { MediaDevices.getDefaultAudioCaptureDevice()?.descriptor }.getOrNull())
        }
        runCatching {
            add(MediaDeviceKind.Speaker, MediaDevices.getAudioRenderDevices(), runCatching { MediaDevices.getDefaultAudioRenderDevice()?.descriptor }.getOrNull())
        }
        runCatching { add(MediaDeviceKind.Camera, cameras(), null) }
        return found
    }

    private fun applyDevice(device: MediaDevice) {
        val asked = System.nanoTime()
        Logger.i("WebRtcJava") { "switch ${device.kind} to ${device.name} requested" }
        var locked = asked
        try {
            synchronized(lock) {
                locked = System.nanoTime()
                applyDeviceLocked(device)
            }
        } finally {
            val now = System.nanoTime()
            Logger.i("WebRtcJava") { "switch ${device.kind} to ${device.name} returned: lockWait=${(locked - asked) / 1_000_000}ms native=${(now - locked) / 1_000_000}ms" }
        }
    }

    private fun applyDeviceLocked(device: MediaDevice) {
        if (closed) return
        val native = nativeDevices[device.kind to device.id] ?: error("${device.name} is no longer present")
        // Before the first pick the call is still on the system default, so that is what a failed switch restores.
        when (device.kind) {
            MediaDeviceKind.Mic -> switchAudio(native as AudioDevice, activeMic ?: runCatching { MediaDevices.getDefaultAudioCaptureDevice() }.getOrNull(), { activeMic = it }) {
                adm.stopRecording(); adm.setRecordingDevice(it); adm.initRecording(); adm.startRecording()
            }
            MediaDeviceKind.Speaker -> switchAudio(native as AudioDevice, activeSpeaker ?: runCatching { MediaDevices.getDefaultAudioRenderDevice() }.getOrNull(), { activeSpeaker = it }) {
                adm.stopPlayout(); adm.setPlayoutDevice(it); adm.initPlayout(); adm.startPlayout()
            }
            MediaDeviceKind.Camera -> switchCaptureDevice(native as VideoDevice)
        }
    }

    private var activeMic: AudioDevice? = null
    private var activeSpeaker: AudioDevice? = null

    // The native ADM throws java.lang.Error (not Exception) when a device refuses to start, e.g. a Continuity mic.
    private fun switchAudio(target: AudioDevice, previous: AudioDevice?, remember: (AudioDevice) -> Unit, start: (AudioDevice) -> Unit) {
        try {
            start(target)
            remember(target)
        } catch (e: Throwable) {
            if (previous != null) runCatching { start(previous) }
            throw IllegalStateException("${target.name} could not be started: ${e.message}", e)
        }
    }

    private fun switchCaptureDevice(device: VideoDevice, tried: List<String> = listOf(device.descriptor), fallback: Boolean = false) {
        val source = videoSource ?: return
        source.stop()
        source.setVideoCaptureDevice(device)
        captureCapability(device)?.let { source.setVideoCaptureCapability(it) }
        localFrames?.mirrored = cameraMirrored(device.name, device.deviceTransport?.name)
        cameraTried = tried
        if (!cameraOn) return
        source.start()
        watchForSilentCamera(tried)
        if (fallback) devices.markApplied(MediaDeviceKind.Camera, device.descriptor)
    }

    private fun captureCapability(device: VideoDevice): VideoCaptureCapability? =
        runCatching { pickCapability(MediaDevices.getVideoCaptureCapabilities(device).map { Capability(it.width, it.height, it.frameRate) }) }
            .getOrNull()?.let { VideoCaptureCapability(it.width, it.height, it.frameRate) }

    // A device that opens but never delivers (a virtual camera whose app is closed) is replaced by the next one.
    private fun watchForSilentCamera(tried: List<String>) {
        val seen = capturedFrames.count.get()
        cameraWatch.schedule(timerTask {
            synchronized(lock) {
                if (closed || !cameraOn || capturedFrames.count.get() != seen) return@synchronized
                val available = cameras()
                val next = nextCameraAfterSilence(available.map { it.descriptor }, tried)
                    ?: return@synchronized Logger.w("WebRtcJava") { "camera delivered no frames and no other camera is left" }
                val device = available.first { it.descriptor == next }
                Logger.w("WebRtcJava") { "camera delivered no frames in ${CAMERA_SILENCE_MS}ms, switching to ${device.name}" }
                runCatching { switchCaptureDevice(device, tried + next, fallback = true) }
                    .onFailure { Logger.w(it, "WebRtcJava") { "fallback camera ${device.name} failed" } }
            }
        }, CAMERA_SILENCE_MS)
    }

    private fun logVideoStats() {
        synchronized(lock) {
            if (closed) return
            peer.getStats { report ->
                val entries = report.stats.values.map { StatsEntry(it.type.name.lowercase().replace('_', '-'), it.attributes, it.id) }
                videoStatsLines(entries, localTrack = videoTrack?.let { if (it.isEnabled) "enabled" else "disabled" }, previous = previousStats)
                    .forEach { line -> Logger.i("WebRtcJava") { line } }
                if (!closed) sendLadder.onStats(entries)?.let { line -> Logger.i("WebRtcJava") { line } }
                previousStats = entries
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

    internal fun isAudioEnabled(): Boolean = audioTrack?.isEnabled == true

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
        mediaState.setCamera(videoTrack != null && cameraOn)
        if (stateChannel == null) openStateChannel()
    }

    // Created after the tracks so the audio and video m-lines keep their indexes; an older peer never opens it.
    private fun openStateChannel() {
        val init = RTCDataChannelInit().apply {
            negotiated = true
            id = MEDIA_STATE_CHANNEL_ID
            ordered = true
        }
        val channel = peer.createDataChannel(MEDIA_STATE_CHANNEL_LABEL, init) ?: return
        synchronized(stateLock) { stateChannel = channel }
        channel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                when (channel.state) {
                    RTCDataChannelState.OPEN -> mediaStateTimer.schedule(timerTask { mediaState.opened(::sendState) }, 0)
                    RTCDataChannelState.CLOSING, RTCDataChannelState.CLOSED -> mediaState.closed()
                    else -> Unit
                }
            }

            override fun onMessage(buffer: RTCDataChannelBuffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                mediaState.received(bytes)
            }
        })
    }

    private fun sendState(bytes: ByteArray): Boolean = synchronized(stateLock) {
        val channel = stateChannel ?: return false
        try {
            channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), true))
            true
        } catch (e: Exception) {
            Logger.w(e, "WebRtcJava") { "media-state send failed" }
            false
        }
    }

    private fun startCamera() {
        try {
            val available = cameras()
            val chosen = devices.selected.value[MediaDeviceKind.Camera]
            val device = available.firstOrNull { it.descriptor == chosen } ?: available.firstOrNull() ?: run {
                Logger.w("WebRtcJava") { "no camera found, continuing audio-only" }
                return
            }
            Logger.i("WebRtcJava") { "camera ${device.name} of ${available.map { it.name }}" }
            val source = VideoDeviceSource().apply {
                setVideoCaptureDevice(device)
                captureCapability(device)?.let { setVideoCaptureCapability(it) }
                start()
            }
            videoSource = source
            val track = factory.createVideoTrack("video0", source)
            // A webcam faces the user, so its preview is mirrored; the transmitted frames are untouched.
            val handle = JvmVideoHandle(cameraMirrored(device.name, device.deviceTransport?.name)).also { localFrames = it }
            track.addSink(handle.sink)
            track.addSink(capturedFrames)
            videoSender = peer.addTrack(track, listOf(STREAM_ID)).also(::configureVideoSender)
            videoTrack = track
            _local.value = handle
            cameraTried = listOf(device.descriptor)
            watchForSilentCamera(cameraTried)
        } catch (e: Exception) {
            Logger.w(e, "WebRtcJava") { "camera unavailable, continuing audio-only" }
        }
    }

    override suspend fun createOffer(video: Boolean): String {
        devices.applyPreferences()
        addLocalMedia(video)
        return offer(RTCOfferOptions())
    }

    override suspend fun answer(offerSdp: String, video: Boolean): String {
        devices.applyPreferences()
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

    private suspend fun offer(options: RTCOfferOptions) = createLocal { peer.createOffer(options, it) }

    private suspend fun createAnswer() = createLocal { peer.createAnswer(RTCAnswerOptions(), it) }

    private suspend fun createLocal(create: (CreateSessionDescriptionObserver) -> Unit): String {
        checkOpen()
        val description = suspendCancellableCoroutine { cont ->
            create(object : CreateSessionDescriptionObserver {
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
        micMuted = muted
        synchronized(lock) { if (!closed) audioTrack?.isEnabled = !muted }
        mediaState.setMic(!muted)
    }

    // The source stops too, so the camera light goes off and the peer is told instead of receiving black frames.
    override fun setVideo(on: Boolean) {
        synchronized(lock) {
            if (closed) return
            val track = videoTrack ?: return
            cameraOn = on
            track.isEnabled = on
            if (on) {
                videoSource?.start()
                watchForSilentCamera(cameraTried)
            } else videoSource?.stop()
        }
        mediaState.setCamera(on)
    }

    override suspend fun addVideo(): String? {
        addLocalMedia(true)
        if (videoTrack == null) return null
        return offer(RTCOfferOptions())
    }

    override fun switchCamera() {
        if (closed || videoSource == null) return
        val cameras = devices.devices.value.filter { it.kind == MediaDeviceKind.Camera }
        if (cameras.size < 2) return
        val current = cameras.indexOfFirst { it.id == devices.selected.value[MediaDeviceKind.Camera] }
        devices.select(MediaDeviceKind.Camera, cameras[(current + 1) % cameras.size].id)
    }

    private fun cameras() = preferredCameraOrder(dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices()) { it.name }

    override fun setAudioRoute(route: AudioRoute) = Unit

    // A native teardown call once spun for 43 minutes with no trace; this puts the stack of the thread stuck in it into the log.
    private inline fun <T> reportIfStalled(call: String, block: () -> T): T {
        val caller = Thread.currentThread()
        val watchdog = Timer("call-close-watchdog", true)
        watchdog.schedule(timerTask {
            Logger.e("WebRtcJava") { "$call still running after ${CLOSE_STALL_MS} ms on ${caller.name}:\n" + caller.stackTrace.joinToString("\n") { "\tat $it" } }
        }, CLOSE_STALL_MS)
        try {
            return block()
        } finally {
            watchdog.cancel()
        }
    }

    override fun close() {
        // Before [lock]: the listener's native thread can be inside a refresh that waits for [lock].
        if (listenerRegistered.compareAndSet(true, false)) {
            reportIfStalled("removeDeviceChangeListener") { runCatching { MediaDevices.removeDeviceChangeListener(deviceListener) } }
        }
        deviceExecutor.shutdownNow()
        micLevelTimer.cancel()
        // Unregistering waits out an open callback, so nothing schedules on the timer once it is cancelled.
        synchronized(stateLock) {
            stateChannel?.let { channel ->
                runCatching { channel.unregisterObserver() }
                runCatching { channel.close() }
                runCatching { channel.dispose() }
            }
            stateChannel = null
        }
        mediaState.closed()
        mediaStateTimer.cancel()
        synchronized(levelLock) { levelStopped = true }
        synchronized(lock) {
            if (closed) return
            closed = true
            statsTimer.cancel()
            cameraWatch.cancel()
            localFrames?.let { sink -> videoTrack?.let { runCatching { it.removeSink(sink.sink) } } }
            synchronized(remoteLock) {
                remoteFrames?.let { sink -> remoteTrack?.let { runCatching { it.removeSink(sink.sink) } } }
                remoteFrames?.close()
                _remote.value = null
            }
            runCatching { peer.close() }
            runCatching { videoSource?.stop() }
            runCatching { audioTrack?.dispose() }
            runCatching { videoTrack?.dispose() }
            runCatching { videoSource?.dispose() }
            localFrames?.close()
            _local.value = null
            runCatching { factory.dispose() }
            if (ownsAudioDevice) runCatching { adm.dispose() }
        }
    }

    private companion object {
        const val STREAM_ID = "homebase-call"
        const val STATS_PERIOD_MS = 5_000L
        const val MIC_LEVEL_PERIOD_MS = 100L
        const val CLOSE_STALL_MS = 10_000L
        const val CAMERA_SILENCE_MS = 3_000L

        // The capturer reports a failed camera start only through the native log, which otherwise goes nowhere.
        val nativeLogs: Unit by lazy {
            Logging.addLogSink(Logging.Severity.WARNING, LogSink { _, message -> Logger.w("WebRTC") { message.trimEnd() } })
        }
    }
}

/**
 * Built-in cameras first and virtual ones last: AVFoundation lists OBS Virtual Camera first, refuses it
 * ("Cannot use OBS Virtual Camera") and the source then delivers no frame without throwing.
 */
internal fun <T> preferredCameraOrder(devices: List<T>, name: (T) -> String): List<T> =
    devices.sortedBy { device ->
        val label = name(device).lowercase()
        when {
            BUILT_IN_CAMERA_HINTS.any { it in label } -> 0
            VIRTUAL_CAMERA_HINTS.any { it in label } -> 2
            else -> 1
        }
    }

private val BUILT_IN_CAMERA_HINTS = listOf("facetime", "built-in", "integrated")
private val VIRTUAL_CAMERA_HINTS = listOf("virtual", "obs", "snap camera", "ndi", "manycam", "mmhmm")

/** Bounds the video sender to [SendVideoCap]'s bitrate window; webrtc-java exposes no degradation preference. */
internal fun configureVideoSender(sender: RTCRtpSender) {
    val params = sender.parameters
    params.encodings?.forEach {
        it.maxBitrate = SendVideoCap.MAX_BITRATE_BPS
        it.minBitrate = SendVideoCap.MIN_BITRATE_BPS
        it.maxFramerate = SendVideoCap.FPS.toDouble()
    }
    sender.parameters = params
}

internal fun scaleVideoSender(sender: RTCRtpSender, scaleDownBy: Double) {
    val params = sender.parameters
    params.encodings?.forEach { it.scaleResolutionDownBy = scaleDownBy }
    sender.parameters = params
}

internal data class Capability(val width: Int, val height: Int, val frameRate: Int)

/** The capture mode nearest 720p at no more than 30 fps; null when the device lists none. */
internal fun pickCapability(options: List<Capability>): Capability? =
    options.minWithOrNull(
        compareBy<Capability> { kotlin.math.abs(it.width * it.height - 1280 * 720) }
            .thenBy { kotlin.math.abs(minOf(it.frameRate, 30) - 30) + if (it.frameRate > 30) 1 else 0 },
    )

/** Webcams face the user, so their preview is mirrored; virtual, HDMI and wireless (Continuity) sources are not. */
internal fun cameraMirrored(name: String, transport: String?): Boolean {
    val label = name.lowercase()
    if (VIRTUAL_CAMERA_HINTS.any { it in label }) return false
    return transport?.uppercase() !in setOf("HDMI", "WIRELESS")
}

internal class FrameCounter : dev.onvoid.webrtc.media.video.VideoTrackSink {
    val count = java.util.concurrent.atomic.AtomicLong()
    override fun onVideoFrame(frame: dev.onvoid.webrtc.media.video.VideoFrame) {
        count.incrementAndGet()
        frame.release()
    }
}

/** The first camera in [order] not yet [tried], or null when every camera has been given a chance. */
internal fun nextCameraAfterSilence(order: List<String>, tried: List<String>): String? = order.firstOrNull { it !in tried }
