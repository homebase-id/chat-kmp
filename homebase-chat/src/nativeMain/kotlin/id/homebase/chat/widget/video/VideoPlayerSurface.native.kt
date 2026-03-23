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
import androidx.compose.ui.interop.UIKitView
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
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
import platform.Foundation.NSURL
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
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
) {
    val driveFileProvider = koinInject<DriveFileProvider>()
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
        withContext(Dispatchers.Main) {
            try {
                val metadata = data.payload.descriptorContent?.let {
                    OdinSystemSerializer.deserialize<VideoMetadata>(it)
                } ?: run {
                    state = VpsState.Error("Missing video metadata")
                    return@withContext
                }

                val hlsPlaylist = metadata.hlsPlaylist
                if (metadata.isSegmented && hlsPlaylist != null) {
                    val strippedPlaylist = hlsPlaylist.lines()
                        .filter { !it.startsWith("#EXT-X-KEY") }
                        .joinToString("\n")

                    val delegate = HomebaseResourceLoaderDelegate(
                        strippedPlaylist = strippedPlaylist,
                        totalFileSize = metadata.fileSize,
                        driveFileProvider = driveFileProvider,
                        driveId = data.driveId,
                        fileId = data.fileId,
                        payloadKey = data.payloadKey,
                        keyHeader = data.keyHeader,
                        scope = scope,
                    )

                    withContext(Dispatchers.Main) {
                        val assetUrl = NSURL.URLWithString("homebase://video/index.m3u8")!!
                        val asset = AVURLAsset(uRL = assetUrl, options = null)
                        val loaderQueue = dispatch_queue_create("id.homebase.video.loader", null)
                        asset.resourceLoader.setDelegate(delegate, queue = loaderQueue)
                        val player = AVPlayer(playerItem = AVPlayerItem(asset = asset))
                        state = VpsState.Playing(player = player, delegate = delegate)
                    }
                } else {
                    // MP4: small file, full download is acceptable
                    val bytesResponse = driveFileProvider.getPayloadBytesDecrypted(
                        driveId = data.driveId,
                        fileId = data.fileId,
                        key = data.payloadKey,
                        keyHeader = data.keyHeader,
                    ) ?: run {
                        state = VpsState.Error("Failed to download video")
                        return@withContext
                    }
                    val dir = NSURL.fileURLWithPath(NSTemporaryDirectory())
                        .URLByAppendingPathComponent("hbvid_${NSUUID().UUIDString()}")!!
                    NSFileManager.defaultManager.createDirectoryAtURL(dir, true, null, null)
                    tempDir = dir
                    val mp4Url = dir.URLByAppendingPathComponent("video.mp4")!!
                    bytesResponse.bytes.toNSData().writeToURL(mp4Url, atomically = true)

                    withContext(Dispatchers.Main) {
                        state = VpsState.Playing(
                            player = AVPlayer(uRL = mp4Url),
                            delegate = HomebaseResourceLoaderDelegate.empty(),
                        )
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
        val path = loadingRequest.request.URL?.path?.trimStart('/') ?: return false

        scope?.launch(Dispatchers.Main) {
            try {
                if (path.endsWith(".m3u8")) {
                    val bytes = strippedPlaylist.encodeToByteArray()
                    loadingRequest.contentInformationRequest?.let {
                        it.contentType = "public.m3u-playlist"
                        it.contentLength = bytes.size.toLong()
                        it.byteRangeAccessSupported = true
                    }
                    loadingRequest.dataRequest?.respondWithData(bytes.toNSData())
                    loadingRequest.finishLoading()
                } else {
                    // .ts segment — serve content info and/or byte-range data on demand
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
                        val bytes = driveFileProvider!!.getPayloadBytesDecrypted(
                            driveId = driveId!!,
                            fileId = fileId!!,
                            key = payloadKey!!,
                            keyHeader = keyHeader!!,
                            chunkStart = start,
                            chunkLength = length,
                        )?.bytes ?: throw Exception("Failed to fetch chunk at $start")
                        dataRequest.respondWithData(bytes.toNSData())
                    }
                    loadingRequest.finishLoading()
                }
            } catch (e: Exception) {
                loadingRequest.finishLoadingWithError(
                    NSError.errorWithDomain("HomebaseVideo", 500, null)
                )
            }
        }
        return true
    }
}

@OptIn(BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}
