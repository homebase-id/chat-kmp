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
import androidx.compose.ui.viewinterop.UIKitView
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.crypto.AesCbc
import id.homebase.api.video.VideoContent
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.VideoPreloader
import id.homebase.api.video.resolveVideoContent
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
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
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.errorLog
import platform.AVFoundation.hasProtectedContent
import platform.AVFoundation.loadValuesAsynchronouslyForKeys
import platform.AVFoundation.loadedTimeRanges
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
import platform.AVFoundation.resourceLoader
import platform.AVFoundation.statusOfValueForKey
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.tracks
import kotlinx.cinterop.useContents
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
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
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_queue_create
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid

private sealed interface VpsState {
    data object Loading : VpsState
    data class Playing(
        val player: AVPlayer,
        val delegate: HomebaseResourceLoaderDelegate, // retain delegate alongside player
        val timeObserver: Any? = null,
    ) : VpsState

    data class Error(val message: String) : VpsState
}

@Composable
actual fun VideoPlayerSurface(
    data: FullScreenOverlay.VideoPlayerData,
    modifier: Modifier,
    onProgress: (Float) -> Unit,
) {
    val driveFileProvider = koinInject<DriveFileProvider>()
    val videoPreloader = koinInject<VideoPreloader>()
    val scope = rememberCoroutineScope()
    var state by remember(data) { mutableStateOf<VpsState>(VpsState.Loading) }
    var tempDir by remember(data) { mutableStateOf<NSURL?>(null) }
    val notificationObservers = remember(data) { mutableListOf<NSObjectProtocol>() }

    DisposableEffect(data) {
        onDispose {
            (state as? VpsState.Playing)?.let { playing ->
                playing.timeObserver?.let { playing.player.removeTimeObserver(it) }
                playing.player.pause()
            }
            notificationObservers.forEach {
                NSNotificationCenter.defaultCenter.removeObserver(it)
            }
            notificationObservers.clear()
            tempDir?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
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
                                Logger.d(tag = "VideoIO") { "hls surface progress: fileId=${data.fileId} p=$p" }
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
                        val delegate = HomebaseResourceLoaderDelegate(
                            strippedPlaylist = content.strippedPlaylist,
                            totalFileSize = content.metadata.fileSize,
                            driveFileProvider = driveFileProvider,
                            driveId = data.driveId,
                            fileId = data.fileId,
                            payloadKey = data.payloadKey,
                            keyHeader = data.keyHeader,
                            scope = scope,
                        )
                        val assetUrl = NSURL.URLWithString("homebase://video/index.m3u8")!!
                        val asset = AVURLAsset(uRL = assetUrl, options = null)
                        val loaderQueue = dispatch_queue_create("id.homebase.video.loader", null)
                        asset.resourceLoader.setDelegate(delegate, queue = loaderQueue)
                        // Kick async load of asset metadata so we get a callback when AVPlayer is
                        // done parsing the playlist + first segment headers. This is the only
                        // point where playable / tracks / duration become reliable.
                        kickAssetMetadataLoad(asset, fileId = data.fileId.toString())
                        val playerItem = AVPlayerItem(asset = asset)
                        attachHlsDiagnostics(playerItem, notificationObservers)
                        val player = AVPlayer(playerItem = playerItem)
                        val timeObserver = attachPlaybackTicker(player, playerItem, fileId = data.fileId.toString())
                        progressJob.cancel()
                        state = VpsState.Playing(player = player, delegate = delegate, timeObserver = timeObserver)
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
                        state = VpsState.Playing(
                            player = AVPlayer(uRL = mp4Url),
                            delegate = HomebaseResourceLoaderDelegate.empty(),
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
            is VpsState.Playing -> UIKitView(
                factory = {
                    AVPlayerViewController().apply {
                        player = s.player
                        s.player.play()
                    }.view
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private class HomebaseResourceLoaderDelegate(
    private val strippedPlaylist: String,
    private val totalFileSize: Long,
    private val driveFileProvider: DriveFileProvider?,
    private val driveId: Uuid?,
    private val fileId: Uuid?,
    private val payloadKey: String?,
    private val keyHeader: KeyHeader?,
    private val scope: CoroutineScope?,
) : NSObject(), AVAssetResourceLoaderDelegateProtocol {

    companion object {
        fun empty() = HomebaseResourceLoaderDelegate("", 0, null, null, null, null, null, null)
    }

    @kotlinx.cinterop.ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        val loadingRequest = shouldWaitForLoadingOfRequestedResource
        val url = loadingRequest.request.URL
        val path = url?.path?.trimStart('/') ?: run {
            Logger.e(tag = "VideoHLS") { "resourceLoader: URL or path is null" }
            return false
        }

        val cInfo = loadingRequest.contentInformationRequest
        val dReq = loadingRequest.dataRequest
        // Each piece on its own line — the iOS file sink truncates long single-line
        // entries and drops everything past the first `\n`, so verbose diagnostics
        // have to be split into several short Logger.d calls.
        Logger.d(tag = "VideoHLS") { "rl req: path=$path file=$fileId key=$payloadKey" }
        Logger.d(tag = "VideoHLS") { "rl req: cInfo=${cInfo != null} dReq=${dReq != null} toEnd=${dReq?.requestsAllDataToEndOfResource}" }
        Logger.d(tag = "VideoHLS") { "rl req: reqOffset=${dReq?.requestedOffset} reqLength=${dReq?.requestedLength} currentOffset=${dReq?.currentOffset}" }

        // Anything that's not the expected playlist or TS chunk is unexpected — log loudly.
        // A request for `enc.key` (or similar) means our playlist still has an EXT-X-KEY
        // directive after stripping, and AVPlayer thinks the asset is encrypted.
        if (!path.endsWith(".m3u8") && !path.endsWith(".ts")) {
            Logger.w(tag = "VideoHLS") { "rl req: UNEXPECTED path=$path — falling through to .ts branch (likely bug)" }
        }

        scope?.launch(Dispatchers.IO) {
            try {
                if (path.endsWith(".m3u8")) {
                    // Surface any EXT-X-KEY directive that survived the line-prefix filter.
                    // The current strip is `lines().filter { !it.startsWith("#EXT-X-KEY") }` —
                    // anything indented or with a different prefix would slip through and tell
                    // AVPlayer the segment data is encrypted, breaking playback silently.
                    if (strippedPlaylist.contains("EXT-X-KEY", ignoreCase = true) ||
                        strippedPlaylist.contains("METHOD=AES", ignoreCase = true)) {
                        Logger.w(tag = "VideoHLS") { "playlist still contains a key directive after stripping — AVPlayer will treat data as encrypted" }
                    }
                    Logger.d(tag = "VideoHLS") { "Serving playlist (${strippedPlaylist.length} chars) for fileId=$fileId" }
                    // Log each line of the playlist as its own short entry so the body
                    // actually lands in homebase.log. Newline-embedded messages get cut.
                    strippedPlaylist.lineSequence().forEachIndexed { idx, line ->
                        Logger.d(tag = "VideoHLS") { "playlist[$idx]: $line" }
                    }
                    // Also dump a sidecar copy on disk so it can be retrieved off-device.
                    runCatching {
                        val tmp = NSURL.fileURLWithPath(NSTemporaryDirectory())
                            .URLByAppendingPathComponent("hbvid_playlist_${fileId ?: "unknown"}.m3u8")
                        if (tmp != null) {
                            strippedPlaylist.encodeToByteArray().toNSData()
                                .writeToURL(tmp, atomically = true)
                            Logger.d(tag = "VideoHLS") { "playlist dumped to ${tmp.path}" }
                        }
                    }.onFailure { Logger.w(tag = "VideoHLS") { "playlist dump failed: ${it.message}" } }
                    val bytes = strippedPlaylist.encodeToByteArray()
                    loadingRequest.contentInformationRequest?.let {
                        it.contentType = "public.m3u8-playlist"
                        it.contentLength = bytes.size.toLong()
                        it.byteRangeAccessSupported = true
                    }
                    val dRespond = loadingRequest.dataRequest
                    Logger.d(tag = "VideoHLS") { "playlist respond: bytes=${bytes.size} dataRequest=${dRespond != null}" }
                    dRespond?.respondWithData(bytes.toNSData())
                    withContext(Dispatchers.Main) {
                        loadingRequest.finishLoading()
                        Logger.d(tag = "VideoHLS") { "playlist finishLoading() called" }
                    }
                } else {
                    // .ts segment — iOS asks for the exact byterange of one HLS segment from
                    // the playlist. FFmpeg encrypted each segment independently (AES-CBC with
                    // PKCS7 padding, keyHeader.iv as IV), so we decrypt standalone and zero-pad
                    // the plaintext back up to `length` to honor Range semantics. AVPlayer's TS
                    // parser resyncs past the trailing zeros on the next 0x47.
                    loadingRequest.contentInformationRequest?.let {
                        it.contentType = "public.mpeg-2-transport-stream"
                        it.contentLength = totalFileSize
                        it.byteRangeAccessSupported = true
                    }
                    val dataRequest = loadingRequest.dataRequest
                    if (dataRequest != null) {
                        val start = dataRequest.requestedOffset
                        val length = if (dataRequest.requestsAllDataToEndOfResource) {
                            totalFileSize - start
                        } else {
                            dataRequest.requestedLength
                        }
                        Logger.d(tag = "VideoHLS") { "avplayer chunk request: fileId=$fileId key=$payloadKey chunkStart=$start chunkLength=$length totalFileSize=$totalFileSize" }
                        if (loadingRequest.isCancelled()) {
                            Logger.w(tag = "VideoHLS") { "ts request: already cancelled before fetch — bailing out" }
                            return@launch
                        }
                        val encrypted = driveFileProvider!!.getPayloadBytesEncryptedChunk(
                            driveId = driveId!!,
                            fileId = fileId!!,
                            key = payloadKey!!,
                            chunkStart = start,
                            chunkLength = length,
                        ) ?: throw Exception("Failed to fetch chunk at $start (length=$length)")
                        if (encrypted.size.toLong() != length) {
                            Logger.w(tag = "VideoHLS") { "ts fetch SHORT-READ: got ${encrypted.size}, expected $length at offset=$start — decrypt likely to fail" }
                        }
                        if (loadingRequest.isCancelled()) {
                            Logger.w(tag = "VideoHLS") { "ts request: cancelled after fetch — bailing out before decrypt" }
                            return@launch
                        }
                        val plaintext = AesCbc.decrypt(
                            cipherText = encrypted,
                            key = keyHeader!!.aesKey,
                            iv = keyHeader.iv,
                        )
                        val requested = length.toInt()
                        val padded = if (plaintext.size >= requested) {
                            plaintext.copyOfRange(0, requested)
                        } else {
                            ByteArray(requested).also { plaintext.copyInto(it, 0) }
                        }
                        // Byte-alignment diagnostics: AES-CBC blocks are 16 bytes, TS packets are 188.
                        // FFmpeg produces plaintext that is N * 188 bytes per segment, then PKCS7
                        // rounds up to the next 16-byte boundary (1..16 pad bytes). After decrypt we
                        // zero-pad the plaintext back up to `requested` so the Range response length
                        // matches what AVPlayer asked for. Each line below is its own short Logger.d
                        // call — the iOS file sink truncates long entries.
                        val cipherHex = encrypted.take(16).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                        val plainHeadHex = plaintext.take(16).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                        val plainTailStart = (plaintext.size - 32).coerceAtLeast(0)
                        val plainTailHex = plaintext.copyOfRange(plainTailStart, plaintext.size).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                        val padTailHex = padded.copyOfRange((padded.size - 32).coerceAtLeast(0), padded.size).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                        val tsRemainder = if (plaintext.isNotEmpty()) plaintext.size % 188 else -1
                        val tsPacketCount = if (plaintext.isNotEmpty()) plaintext.size / 188 else -1
                        val firstByteIsSync = plaintext.isNotEmpty() && plaintext[0] == 0x47.toByte()
                        Logger.d(tag = "VideoHLS") { "decrypt sizes: cipher=${encrypted.size} plain=${plaintext.size} padded=${padded.size} at=$start" }
                        Logger.d(tag = "VideoHLS") { "decrypt align: startMod16=${start % 16} lenMod16=${length % 16} plainMod188=$tsRemainder tsPackets=$tsPacketCount firstByte=0x47?=$firstByteIsSync" }
                        Logger.d(tag = "VideoHLS") { "decrypt cipher[0..16]=$cipherHex" }
                        Logger.d(tag = "VideoHLS") { "decrypt plain[0..16]=$plainHeadHex" }
                        Logger.d(tag = "VideoHLS") { "decrypt plain[tail-32..]=$plainTailHex" }
                        Logger.d(tag = "VideoHLS") { "decrypt padded[tail-32..]=$padTailHex" }
                        if (loadingRequest.isCancelled()) {
                            Logger.w(tag = "VideoHLS") { "ts request: cancelled after decrypt — skipping respondWithData/finishLoading" }
                            return@launch
                        }
                        Logger.d(tag = "VideoHLS") { "ts respond: handing ${padded.size} bytes to AVPlayer at offset=$start" }
                        dataRequest.respondWithData(padded.toNSData())
                    } else {
                        Logger.w(tag = "VideoHLS") { "ts request had no dataRequest — nothing to respond with" }
                    }
                    withContext(Dispatchers.Main) {
                        if (loadingRequest.isCancelled()) {
                            Logger.w(tag = "VideoHLS") { "ts request: cancelled before finishLoading — skipping" }
                        } else {
                            loadingRequest.finishLoading()
                            Logger.d(tag = "VideoHLS") { "ts finishLoading() called for fileId=$fileId" }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "VideoHLS") { "resourceLoader error path=$path file=$fileId: ${e::class.simpleName}: ${e.message}" }
                withContext(Dispatchers.Main) {
                    if (!loadingRequest.isCancelled()) {
                        loadingRequest.finishLoadingWithError(
                            NSError.errorWithDomain("HomebaseVideo", 500, null)
                        )
                        Logger.d(tag = "VideoHLS") { "finishLoadingWithError() called" }
                    } else {
                        Logger.d(tag = "VideoHLS") { "request was cancelled — not calling finishLoadingWithError" }
                    }
                }
            }
        }
        return true
    }

    /**
     * Fires when AVPlayer cancels a previously-issued loading request. We log it so
     * that "no further chunk requests" in the log can be distinguished from "cancelled
     * what we already had outstanding" — both look identical otherwise.
     */
    @kotlinx.cinterop.ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        didCancelLoadingRequest: AVAssetResourceLoadingRequest,
    ) {
        val req = didCancelLoadingRequest
        val path = req.request.URL?.path?.trimStart('/') ?: "?"
        val dReq = req.dataRequest
        Logger.w(tag = "VideoHLS") { "rl CANCELLED: path=$path file=$fileId reqOffset=${dReq?.requestedOffset} reqLength=${dReq?.requestedLength}" }
    }
}

@OptIn(BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
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
