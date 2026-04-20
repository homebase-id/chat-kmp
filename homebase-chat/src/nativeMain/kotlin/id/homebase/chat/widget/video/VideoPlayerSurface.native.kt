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
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.resourceLoader
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid

private sealed interface VpsState {
    data object Loading : VpsState
    data class Playing(
        val player: AVPlayer,
        val delegate: HomebaseResourceLoaderDelegate, // retain delegate alongside player
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

    DisposableEffect(data) {
        onDispose {
            (state as? VpsState.Playing)?.player?.pause()
            tempDir?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        }
    }

    LaunchedEffect(data) {
        onProgress(0f)
        withContext(Dispatchers.Main) {
            try {
                when (val content = resolveVideoContent(VideoPlayerData(data.fileId, data.driveId, data.payloadKey, data.keyHeader, data.payload.descriptorContent), driveFileProvider, onDownloadProgress = { onProgress(it * 0.5f) })) {
                    is VideoContent.Hls -> {
                        onProgress(0.5f)
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
                        val player = AVPlayer(playerItem = AVPlayerItem(asset = asset))
                        onProgress(0.8f)
                        state = VpsState.Playing(player = player, delegate = delegate)
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

        Logger.d(tag = "VideoHLS") { "resourceLoader request: url=${url}, path=$path" }

        scope?.launch(Dispatchers.IO) {
            try {
                if (path.endsWith(".m3u8")) {
                    Logger.d(tag = "VideoHLS") { "Serving playlist (${strippedPlaylist.length} chars)" }
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
                        Logger.d(tag = "VideoHLS") {
                            "Segment decrypt: ${encrypted.size} cipher → ${plaintext.size} plain → ${padded.size} zero-padded at $start"
                        }
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
