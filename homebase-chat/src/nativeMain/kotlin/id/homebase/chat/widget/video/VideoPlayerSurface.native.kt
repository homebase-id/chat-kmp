@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.chat.widget.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.video.VideoContent
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.VideoPreloader
import id.homebase.api.video.resolveVideoContent
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemNewAccessLogEntryNotification
import platform.AVFoundation.AVPlayerItemNewErrorLogEntryNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemStatusUnknown
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVPlayerItemTrack
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.accessLog
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.errorLog
import platform.AVFoundation.hasProtectedContent
import platform.AVFoundation.loadValuesAsynchronouslyForKeys
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.mediaType
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.playable
import platform.AVFoundation.playbackBufferEmpty
import platform.AVFoundation.playbackBufferFull
import platform.AVFoundation.playbackLikelyToKeepUp
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.reasonForWaitingToPlay
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setMuted
import platform.AVFoundation.statusOfValueForKey
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.tracks
import kotlinx.cinterop.useContents
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.darwin.NSObjectProtocol
import kotlin.time.measureTimedValue

private sealed interface VpsState {
    data object Loading : VpsState
    data class Playing(
        val player: AVPlayer,
        val timeObserver: Any? = null,
        // Present for HLS playback; null for MP4. Used to unregister the LocalVideoServer
        // session on dispose so the encrypted-bytes endpoint can't be hit after the player
        // tears down.
        val sessionId: String? = null,
    ) : VpsState

    data class Error(val message: String) : VpsState
}

@Composable
actual fun VideoPlayerSurface(
    data: FullScreenOverlay.VideoPlayerData,
    modifier: Modifier,
    onProgress: (Float) -> Unit,
    muted: Boolean,
    useNativeControls: Boolean,
    useInlineOptimizations: Boolean,
    startPositionMs: Long,
    onPositionUpdate: (Long) -> Unit,
    onFirstFrame: () -> Unit,
    useZoomFill: Boolean,
    onEnded: () -> Unit,
    replayToken: Int,
    paused: Boolean,
) {
    val driveFileProvider = koinInject<DriveFileProvider>()
    val videoPreloader = koinInject<VideoPreloader>()
    val playerPool = koinInject<AVPlayerPool>()
    val scope = rememberCoroutineScope()
    var state by remember(data) { mutableStateOf<VpsState>(VpsState.Loading) }
    var tempDir by remember(data) { mutableStateOf<NSURL?>(null) }
    val notificationObservers = remember(data) { mutableListOf<NSObjectProtocol>() }

    DisposableEffect(data) {
        onDispose {
            (state as? VpsState.Playing)?.let { playing ->
                playing.timeObserver?.let { playing.player.removeTimeObserver(it) }
                playing.player.pause()
                playing.sessionId?.let { id ->
                    // Server itself stays alive for the app lifetime; just drop the session
                    // so the encrypted-bytes endpoint can't be hit on a stale id.
                    scope.launch { LocalVideoServer.shared().unregister(id) }
                }
                // Observers + sessionId must be torn down BEFORE returning
                // the player to the pool — otherwise the recycled player
                // would keep firing notifications + log entries for the
                // wrong session. notificationObservers.forEach below does
                // that for NSNotificationCenter; the time observer was
                // already removed above.
                if (useInlineOptimizations) {
                    playerPool.release(playing.player)
                }
            }
            notificationObservers.forEach {
                NSNotificationCenter.defaultCenter.removeObserver(it)
            }
            notificationObservers.clear()
            tempDir?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        }
    }

    // Pump the current playback position back to the caller every ~500 ms so
    // the moments inline tile can keep MomentsVideoSession's handoff slot
    // fresh. Mirrors the Android side. Off-thread isn't required —
    // `player.currentTime()` is cheap and main-safe.
    LaunchedEffect(state) {
        val player = (state as? VpsState.Playing)?.player ?: return@LaunchedEffect
        while (true) {
            val seconds = CMTimeGetSeconds(player.currentTime())
            if (seconds.isFinite() && seconds > 0.0) {
                onPositionUpdate((seconds * 1000.0).toLong())
            }
            kotlinx.coroutines.delay(500)
        }
    }

    LaunchedEffect(state, muted) {
        val player = (state as? VpsState.Playing)?.player ?: return@LaunchedEffect
        player.setMuted(muted)
        // When the inline-optimization flag is on, also disable the audio
        // track itself so the AAC decoder never runs on a muted tile. Pure
        // `setMuted` is a mixer-level knob — the decoder still spins up.
        // Disabling the track stops the decoder entirely. Safe to toggle
        // mid-playback per AVFoundation docs; the renderer attaches when we
        // re-enable on unmute.
        //
        // playerItem.tracks is populated by the asset metadata load, so this
        // only finds tracks once the playerItem has reached at least
        // `.readyToPlay` — which is the moment we flip state to Playing in
        // the opt-in path. For the non-opt-in path the loop is a no-op
        // anyway (gate below is false).
        if (useInlineOptimizations) {
            player.currentItem?.tracks?.forEach { track ->
                val pit = track as? AVPlayerItemTrack ?: return@forEach
                if (pit.assetTrack?.mediaType == AVMediaTypeAudio) {
                    pit.setEnabled(!muted)
                }
            }
        }
    }

    // Press-and-hold pause-in-place. AVPlayer.pause() holds the current frame
    // on the layer and play() resumes from the same time — no teardown, no
    // reset. Mirrors the Android playWhenReady toggle.
    LaunchedEffect(state, paused) {
        val player = (state as? VpsState.Playing)?.player ?: return@LaunchedEffect
        if (paused) player.pause() else player.play()
    }

    // Replay-in-place. The "Watch again" button increments replayToken; seek
    // the (ended) player back to zero and resume without remounting. Guarded
    // on > 0 so the initial composition doesn't fire a spurious seek.
    LaunchedEffect(replayToken) {
        if (replayToken > 0) {
            (state as? VpsState.Playing)?.player?.let { player ->
                player.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
                player.play()
            }
        }
    }

    LaunchedEffect(data) {
        onProgress(0f)
        withContext(Dispatchers.Main) {
            try {
                when (val content = resolveVideoContent(VideoPlayerData(data.fileId, data.driveId, data.payloadKey, data.keyHeader, data.payload.descriptorContent), driveFileProvider, onDownloadProgress = { onProgress(it * 0.5f) })) {
                    is VideoContent.Hls -> {
                        // Subscribe to the preloader's live bytes progress BEFORE kicking off the
                        // preload, so StateFlow's initial value and every subsequent emit lands.
                        val progressJob = scope.launch {
                            var highWater = 0f
                            videoPreloader.progressFlow(data.fileId, data.payloadKey).collect { p ->
                                // Logger.d(tag = "VideoIO") { "hls surface progress: fileId=${data.fileId} p=$p" } // floods log — fires per ~4KB HTTP chunk
                                if (p > highWater) highWater = p
                                if (highWater < 1f) onProgress(highWater)
                            }
                        }
                        // Await the preload so the first segment is cached before AVPlayer starts.
                        // If MediaItem's preload was cancelled when the chat list left composition,
                        // this is the only path that drives real progress — AVPlayer's resource loader
                        // delegate bypasses onDownloadProgress entirely.
                        videoPreloader.preload(
                            VideoPlayerData(data.fileId, data.driveId, data.payloadKey, data.keyHeader, data.payload.descriptorContent)
                        )

                        // Hand AVPlayer a real `http://127.0.0.1:<port>/...` URL backed by a
                        // tiny CIO server. The server rewrites the playlist so #EXT-X-KEY
                        // points at the AES key inline as a data: URI and segment URIs come
                        // back to /segment, which streams ciphertext untouched. AVPlayer
                        // decrypts via stock HLS-AES-128 — no resource loader, no per-byterange
                        // crypto on our side. See LocalVideoServer.native.kt for the rationale.
                        val server = LocalVideoServer.shared()
                        val sessionId = server.register(
                            driveFileProvider = driveFileProvider,
                            driveId = data.driveId,
                            fileId = data.fileId,
                            payloadKey = data.payloadKey,
                            keyHeader = data.keyHeader,
                            originalPlaylist = content.originalPlaylist,
                            totalFileSize = content.metadata.fileSize,
                        )
                        val assetUrl = NSURL.URLWithString(server.manifestUrl(sessionId))!!
                        val asset = AVURLAsset(uRL = assetUrl, options = null)
                        // Kick async load of asset metadata so we get a callback when AVPlayer is
                        // done parsing the playlist + first segment headers. This is the only
                        // point where playable / tracks / duration become reliable.
                        kickAssetMetadataLoad(asset, fileId = data.fileId.toString())
                        val playerItem = AVPlayerItem(asset = asset)
                        attachHlsDiagnostics(playerItem, notificationObservers)
                        observeEndOfPlayback(playerItem, notificationObservers, onEnded)
                        val player = if (useInlineOptimizations) {
                            playerPool.acquire().also {
                                it.replaceCurrentItemWithPlayerItem(playerItem)
                            }
                        } else {
                            AVPlayer(playerItem = playerItem)
                        }
                        val timeObserver = attachPlaybackTicker(player, playerItem, fileId = data.fileId.toString())
                        progressJob.cancel()
                        // Inline-optimization path waits for AVPlayerItem
                        // status → .readyToPlay before flipping state, so
                        // the spinner stays up until the asset has actually
                        // parsed. Without this gate, UIKitViewController
                        // mounts on a not-yet-ready item and shows a black
                        // square until the first frame arrives (Signal's
                        // `onPlaybackReady()` analog).
                        if (useInlineOptimizations) {
                            awaitReadyToPlay(playerItem)
                        }
                        // Resume from the moments feed↔detail handoff
                        // position. AVPlayer queues seek if the asset isn't
                        // fully ready, so this is safe to call regardless of
                        // the readyToPlay gate above.
                        if (startPositionMs > 0L) {
                            player.seekToTime(CMTimeMakeWithSeconds(startPositionMs / 1000.0, 600))
                        }
                        state = VpsState.Playing(player = player, timeObserver = timeObserver, sessionId = sessionId)
                        onProgress(1f)
                    }
                    is VideoContent.Mp4 -> {
                        onProgress(0.5f)
                        val preloadedPath = videoPreloader.awaitPreloadedFile(data.fileId, data.payloadKey)
                        val mp4Url = if (preloadedPath != null) {
                            Logger.d(tag = "VideoIO") { "mp4 using preloaded file" }
                            NSURL.fileURLWithPath(preloadedPath)
                        } else {
                            val dir = NSURL.fileURLWithPath(NSTemporaryDirectory())
                                .URLByAppendingPathComponent("hbvid_${NSUUID().UUIDString()}")!!
                            NSFileManager.defaultManager.createDirectoryAtURL(dir, true, null, null)
                            tempDir = dir
                            val url = dir.URLByAppendingPathComponent("video.mp4")!!
                            val (_, writeElapsed) = measureTimedValue {
                                content.bytes.toNSData().writeToURL(url, atomically = true)
                            }
                            Logger.d(tag = "VideoIO") { "mp4 temp-file write: ${content.bytes.size} bytes in $writeElapsed" }
                            url
                        }
                        onProgress(0.8f)
                        val player = if (useInlineOptimizations) {
                            val item = AVPlayerItem(uRL = mp4Url)
                            playerPool.acquire().also {
                                it.replaceCurrentItemWithPlayerItem(item)
                            }
                        } else {
                            AVPlayer(uRL = mp4Url)
                        }
                        player.currentItem?.let { item ->
                            observeEndOfPlayback(item, notificationObservers, onEnded)
                        }
                        if (useInlineOptimizations) {
                            // Same `.readyToPlay` gate as the HLS path —
                            // wait for asset parse before mounting the
                            // AVPlayerViewController so the spinner
                            // smoothly hands over to the first frame.
                            player.currentItem?.let { awaitReadyToPlay(it) }
                        }
                        if (startPositionMs > 0L) {
                            player.seekToTime(CMTimeMakeWithSeconds(startPositionMs / 1000.0, 600))
                        }
                        state = VpsState.Playing(
                            player = player,
                        )
                        onProgress(1f)
                    }
                }
            } catch (e: Exception) {
                state = VpsState.Error(e.message ?: "Playback error")
            }
        }
    }

    Box(modifier) {
        when (val s = state) {
            VpsState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is VpsState.Error -> Text(text = s.message, modifier = Modifier.align(Alignment.Center))
            // UIKitViewController (not UIKitView) — Compose handles the
            // addChild/didMove(toParent:) containment dance UIViewControllers
            // require. AVPlayerViewController will not initialize its secure
            // rendering layer correctly when its `.view` is hosted bare in a
            // UIKitView (no parent VC), which manifests as audio-plays /
            // video-stays-black for assets where hasProtectedContent=true —
            // exactly what HLS-AES-128 sets after we stopped stripping
            // #EXT-X-KEY.
            is VpsState.Playing -> UIKitViewController(
                factory = {
                    AVPlayerViewController().apply {
                        player = s.player
                        // AVPlayerViewController's built-in transport controls
                        // capture every touch — the carousel pager never sees
                        // the horizontal drag. Suppress when the host renders
                        // its own play/pause affordance (moments-feed
                        // carousel).
                        showsPlaybackControls = useNativeControls
                        // Fit-with-letterbox vs crop-to-fill, driven solely by
                        // [useZoomFill]. Inline tiles request crop-to-fill
                        // explicitly to match their crop-to-fill thumbnail; the
                        // comments-open shrink passes useZoomFill = false so the
                        // whole frame shows above the sheet.
                        videoGravity = if (useZoomFill) {
                            AVLayerVideoGravityResizeAspectFill
                        } else {
                            AVLayerVideoGravityResizeAspect
                        }
                        s.player.play()
                        // Approximate "first frame ready" signal: status is
                        // already .readyToPlay (awaitReadyToPlay gated us
                        // here) and the AVPlayerViewController has just been
                        // configured. The actual first decoded frame paints a
                        // few ms later — close enough for the inline tile's
                        // thumbnail-overlay drop.
                        onFirstFrame()
                    }
                },
                update = { controller ->
                    (controller as? AVPlayerViewController)?.videoGravity =
                        if (useZoomFill) {
                            AVLayerVideoGravityResizeAspectFill
                        } else {
                            AVLayerVideoGravityResizeAspect
                        }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

/**
 * Suspend until [playerItem] reports `.readyToPlay`. Returns early on
 * `.failed` so the calling state machine can fall through and present
 * whatever AVPlayer surfaces (or stay in Loading and let the periodic
 * diagnostic ticker reveal the failure). Capped at 10 s so a dead asset
 * doesn't trap the spinner forever — the existing non-opt-in path also
 * silently shows a black square in that scenario, so this is at worst the
 * same outcome.
 *
 * The poll cadence is intentionally tight (20 ms): the wait is happening on
 * the main thread inside a `LaunchedEffect`, and a fast cadence is what
 * makes the "spinner → first frame" handover feel instant on a healthy
 * asset. KVO would be more elegant but requires NSObject subclassing in
 * Kotlin/Native, which is more glue than this gate is worth.
 */
private suspend fun awaitReadyToPlay(playerItem: AVPlayerItem) {
    withTimeoutOrNull(10_000) {
        while (true) {
            when (playerItem.status) {
                AVPlayerItemStatusReadyToPlay, AVPlayerItemStatusFailed -> return@withTimeoutOrNull
                else -> delay(20)
            }
        }
    }
}

/**
 * Fire [onEnded] when [playerItem] plays to its end. Registered for both HLS
 * and MP4 items; the observer token is parked in [observers] so the shared
 * dispose path removes it before the pooled player is recycled.
 */
private fun observeEndOfPlayback(
    playerItem: AVPlayerItem,
    observers: MutableList<NSObjectProtocol>,
    onEnded: () -> Unit,
) {
    observers += NSNotificationCenter.defaultCenter.addObserverForName(
        name = AVPlayerItemDidPlayToEndTimeNotification,
        `object` = playerItem,
        queue = NSOperationQueue.mainQueue,
        usingBlock = { onEnded() },
    )
}

private fun attachHlsDiagnostics(
    playerItem: AVPlayerItem,
    observers: MutableList<NSObjectProtocol>,
) {
    val center = NSNotificationCenter.defaultCenter
    val mainQueue = NSOperationQueue.mainQueue

    fun statusName(status: Long): String = when (status) {
        AVPlayerItemStatusUnknown -> "Unknown"
        AVPlayerItemStatusReadyToPlay -> "ReadyToPlay"
        AVPlayerItemStatusFailed -> "Failed"
        else -> "raw=$status"
    }

    val onFailed: (NSNotification?) -> Unit = { note ->
        val err = note?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError
        Logger.e(tag = "VideoHLS") {
            "AVPlayerItem FailedToPlayToEndTime: status=${statusName(playerItem.status)} " +
                "itemError=${playerItem.error?.let { "${it.domain}:${it.code} ${it.localizedDescription}" }} " +
                "userInfoError=${err?.let { "${it.domain}:${it.code} ${it.localizedDescription}" }}"
        }
    }
    val onStalled: (NSNotification?) -> Unit = {
        Logger.w(tag = "VideoHLS") {
            "AVPlayerItem PlaybackStalled: status=${statusName(playerItem.status)} " +
                "itemError=${playerItem.error?.let { "${it.domain}:${it.code} ${it.localizedDescription}" }}"
        }
    }
    val onErrorLog: (NSNotification?) -> Unit = {
        val log = playerItem.errorLog()
        val events = log?.events.orEmpty()
        Logger.e(tag = "VideoHLS") {
            "AVPlayerItem NewErrorLogEntry: status=${statusName(playerItem.status)} eventCount=${events.size}"
        }
        // Dump every event — multi-error sequences carry the actual root cause in the
        // first event, not the last (which is often a generic "stream couldn't be parsed").
        events.takeLast(10).forEachIndexed { idx, ev ->
            Logger.e(tag = "VideoHLS") { "errorLog[$idx]: $ev" }
        }
    }
    val onAccessLog: (NSNotification?) -> Unit = {
        val log = playerItem.accessLog()
        val events = log?.events.orEmpty()
        Logger.d(tag = "VideoHLS") {
            "AVPlayerItem NewAccessLogEntry: eventCount=${events.size}"
        }
        events.takeLast(3).forEachIndexed { idx, ev ->
            Logger.d(tag = "VideoHLS") { "accessLog[$idx]: $ev" }
        }
    }

    observers += center.addObserverForName(
        name = AVPlayerItemFailedToPlayToEndTimeNotification,
        `object` = playerItem,
        queue = mainQueue,
        usingBlock = onFailed,
    )
    observers += center.addObserverForName(
        name = AVPlayerItemPlaybackStalledNotification,
        `object` = playerItem,
        queue = mainQueue,
        usingBlock = onStalled,
    )
    observers += center.addObserverForName(
        name = AVPlayerItemNewErrorLogEntryNotification,
        `object` = playerItem,
        queue = mainQueue,
        usingBlock = onErrorLog,
    )
    observers += center.addObserverForName(
        name = AVPlayerItemNewAccessLogEntryNotification,
        `object` = playerItem,
        queue = mainQueue,
        usingBlock = onAccessLog,
    )
    Logger.d(tag = "VideoHLS") { "diagnostics attached: ${observers.size} notification observers" }
}

/**
 * Periodic tick (every 0.5 s) that logs player state — the silent-failure mode we're
 * chasing produces no AVPlayerItem notifications at all, so we need an out-of-band
 * heartbeat that tells us whether status ever flips to ReadyToPlay, whether rate goes
 * non-zero, and what currentTime is doing. Returns the opaque observer token; callers
 * must hand it back to AVPlayer.removeTimeObserver on dispose.
 */
private fun attachPlaybackTicker(
    player: AVPlayer,
    playerItem: AVPlayerItem,
    fileId: String,
): Any? {
    fun statusName(status: Long): String = when (status) {
        AVPlayerItemStatusUnknown -> "Unknown"
        AVPlayerItemStatusReadyToPlay -> "ReadyToPlay"
        AVPlayerItemStatusFailed -> "Failed"
        else -> "raw=$status"
    }

    var tickCount = 0
    var lastStatus = -1L
    var lastRateBucket = Float.NaN
    var lastErrorDesc: String? = "<initial>"
    var lastTrackCount = -1
    var lastTimeControl = -1L
    var lastReason: String? = "<initial>"
    var lastBufEmpty: Boolean? = null
    var lastBufLikely: Boolean? = null

    fun timeControlName(s: Long): String = when (s) {
        AVPlayerTimeControlStatusPaused -> "Paused"
        AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> "Waiting"
        AVPlayerTimeControlStatusPlaying -> "Playing"
        else -> "raw=$s"
    }
    val interval = CMTimeMakeWithSeconds(0.5, 600)
    val token = player.addPeriodicTimeObserverForInterval(
        interval = interval,
        queue = null, // main queue
    ) { _ ->
        tickCount += 1
        val status = playerItem.status
        val rate = player.rate
        val timeControl = player.timeControlStatus
        val reason: String? = player.reasonForWaitingToPlay
        val currentSec = CMTimeGetSeconds(player.currentTime())
        val durSec = CMTimeGetSeconds(playerItem.duration)
        val itemErr = playerItem.error?.let { "${it.domain}:${it.code} ${it.localizedDescription}" }
        val playerErr = player.error?.let { "${it.domain}:${it.code} ${it.localizedDescription}" }
        val errDesc = listOfNotNull(itemErr?.let { "item=$it" }, playerErr?.let { "player=$it" }).joinToString(" | ").ifEmpty { null }
        val tracks = playerItem.tracks
        val trackCount = tracks.size
        val loadedCount = playerItem.loadedTimeRanges.size
        val loadedSummary = if (loadedCount == 0) "none" else "$loadedCount ranges"
        val bufEmpty = playerItem.playbackBufferEmpty
        val bufLikely = playerItem.playbackLikelyToKeepUp
        val bufFull = playerItem.playbackBufferFull
        val presW = playerItem.presentationSize.useContents { width }
        val presH = playerItem.presentationSize.useContents { height }
        val statusChanged = status != lastStatus
        val rateChanged = rate != lastRateBucket
        val errorChanged = errDesc != lastErrorDesc
        val trackCountChanged = trackCount != lastTrackCount
        val timeControlChanged = timeControl != lastTimeControl
        val reasonChanged = reason != lastReason
        val bufFlagsChanged = bufEmpty != lastBufEmpty || bufLikely != lastBufLikely

        // Always log changes; otherwise heartbeat every ~2 s (every 4th tick).
        if (statusChanged || rateChanged || errorChanged || trackCountChanged ||
            timeControlChanged || reasonChanged || bufFlagsChanged || tickCount % 4 == 0) {
            Logger.d(tag = "VideoHLS") {
                "tick#$tickCount fileId=$fileId status=${statusName(status)} rate=$rate t=${currentSec}s dur=${durSec}s tracks=$trackCount"
            }
            Logger.d(tag = "VideoHLS") { "tick#$tickCount timeControl=${timeControlName(timeControl)} reason=$reason" }
            Logger.d(tag = "VideoHLS") { "tick#$tickCount loaded=$loadedSummary bufEmpty=$bufEmpty bufLikely=$bufLikely bufFull=$bufFull" }
            Logger.d(tag = "VideoHLS") { "tick#$tickCount presentationSize=${presW}x${presH}" }
            if (errDesc != null) {
                Logger.d(tag = "VideoHLS") { "tick#$tickCount errors: $errDesc" }
            }
            if (statusChanged) {
                Logger.d(tag = "VideoHLS") {
                    "tick#$tickCount status TRANSITION ${statusName(lastStatus)} → ${statusName(status)}"
                }
                // On any status change, dump per-track info — the silent black-screen mode
                // typically has tracks=0 even at ReadyToPlay, which is the smoking gun.
                tracks.forEachIndexed { idx, t ->
                    // Avoid binding-specific accessors that vary across Kotlin/Native versions —
                    // `description` round-trips through Objective-C and embeds mediaType + enabled.
                    val track = t as? AVPlayerItemTrack
                    Logger.d(tag = "VideoHLS") {
                        "tick#$tickCount track[$idx]: ${track?.description}"
                    }
                }
            }
        }
        lastStatus = status
        lastRateBucket = rate
        lastErrorDesc = errDesc
        lastTrackCount = trackCount
        lastTimeControl = timeControl
        lastReason = reason
        lastBufEmpty = bufEmpty
        lastBufLikely = bufLikely
    }
    Logger.d(tag = "VideoHLS") { "playback ticker attached for fileId=$fileId" }
    return token
}

/**
 * Async-load the asset's `playable`, `tracks`, and `duration` keys, then log the
 * resolved values. AVPlayer goes through this same machinery internally, but the
 * silent black-screen failure mode never surfaces the result anywhere we can see.
 * Logging it here is the most direct way to confirm whether the playlist parses
 * into a playable asset at all.
 */
private fun kickAssetMetadataLoad(asset: AVURLAsset, fileId: String) {
    val keys = listOf("playable", "tracks", "duration", "hasProtectedContent")
    val kickedAtMs = NSDate().timeIntervalSince1970 * 1000.0
    asset.loadValuesAsynchronouslyForKeys(keys) {
        // Distinguish "callback fired" from "callback never fired" — the latter is a
        // legitimate failure mode if AVPlayer/asset is torn down before completion.
        val nowMs = NSDate().timeIntervalSince1970 * 1000.0
        Logger.d(tag = "VideoHLS") { "asset metadata callback fired after ${(nowMs - kickedAtMs).toLong()}ms fileId=$fileId" }
        keys.forEach { key ->
            val status = asset.statusOfValueForKey(key, error = null)
            Logger.d(tag = "VideoHLS") { "asset.$key load status=$status fileId=$fileId" }
        }
        Logger.d(tag = "VideoHLS") { "asset playable=${asset.playable} hasProtectedContent=${asset.hasProtectedContent} fileId=$fileId" }
        val tracks = asset.tracks
        Logger.d(tag = "VideoHLS") { "asset tracks=${tracks.size} fileId=$fileId" }
        tracks.forEachIndexed { idx, t ->
            // `description` includes mediaType, enabled flag, etc — avoids binding accessor pitfalls.
            val track = t as? AVAssetTrack
            Logger.d(tag = "VideoHLS") { "asset track[$idx]: ${track?.description}" }
        }
        val durSec = CMTimeGetSeconds(asset.duration)
        Logger.d(tag = "VideoHLS") { "asset duration=${durSec}s fileId=$fileId" }
    }
    Logger.d(tag = "VideoHLS") { "asset.loadValuesAsynchronouslyForKeys kicked for fileId=$fileId at ${kickedAtMs.toLong()}ms" }
}
