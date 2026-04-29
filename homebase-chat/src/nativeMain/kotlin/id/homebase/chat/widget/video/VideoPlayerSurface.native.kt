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
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.accessLog
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentTime
import platform.AVFoundation.errorLog
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.resourceLoader
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
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

        scope?.launch(Dispatchers.IO) {
            try {
                if (path.endsWith(".m3u8")) {
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
                    loadingRequest.dataRequest?.respondWithData(bytes.toNSData())
                    withContext(Dispatchers.Main) { loadingRequest.finishLoading() }
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
                        val encrypted = driveFileProvider!!.getPayloadBytesEncryptedChunk(
                            driveId = driveId!!,
                            fileId = fileId!!,
                            key = payloadKey!!,
                            chunkStart = start,
                            chunkLength = length,
                        ) ?: throw Exception("Failed to fetch chunk at $start (length=$length)")
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
                        dataRequest.respondWithData(padded.toNSData())
                    }
                    withContext(Dispatchers.Main) { loadingRequest.finishLoading() }
                }
            } catch (e: Exception) {
                Logger.e(tag = "VideoHLS") { "resourceLoader error: ${e.message}" }
                withContext(Dispatchers.Main) {
                    loadingRequest.finishLoadingWithError(
                        NSError.errorWithDomain("HomebaseVideo", 500, null)
                    )
                }
            }
        }
        return true
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
        val last = log?.events?.lastOrNull()
        Logger.e(tag = "VideoHLS") {
            "AVPlayerItem NewErrorLogEntry: status=${statusName(playerItem.status)} last=$last"
        }
    }
    val onAccessLog: (NSNotification?) -> Unit = {
        val log = playerItem.accessLog()
        val last = log?.events?.lastOrNull()
        Logger.d(tag = "VideoHLS") {
            "AVPlayerItem NewAccessLogEntry: last=$last"
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
    val interval = CMTimeMakeWithSeconds(0.5, 600)
    val token = player.addPeriodicTimeObserverForInterval(
        interval = interval,
        queue = null, // main queue
    ) { _ ->
        tickCount += 1
        val status = playerItem.status
        val rate = player.rate
        val currentSec = CMTimeGetSeconds(player.currentTime())
        // Always log status transitions; otherwise log every ~2 s (every 4th tick).
        val statusChanged = status != lastStatus
        val rateChanged = rate != lastRateBucket
        if (statusChanged || rateChanged || tickCount % 4 == 0) {
            Logger.d(tag = "VideoHLS") {
                "tick#$tickCount fileId=$fileId status=${statusName(status)} rate=$rate t=${currentSec}s"
            }
            if (statusChanged) {
                Logger.d(tag = "VideoHLS") {
                    "tick#$tickCount status TRANSITION ${statusName(lastStatus)} → ${statusName(status)} itemError=${playerItem.error?.let { "${it.domain}:${it.code} ${it.localizedDescription}" }}"
                }
            }
        }
        lastStatus = status
        lastRateBucket = rate
    }
    Logger.d(tag = "VideoHLS") { "playback ticker attached for fileId=$fileId" }
    return token
}
