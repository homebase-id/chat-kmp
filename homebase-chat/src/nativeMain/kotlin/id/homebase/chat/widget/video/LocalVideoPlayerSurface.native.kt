@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.seekToTime
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL

@Composable
actual fun LocalVideoPlayerSurface(
    filePath: String,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit,
) {
    val player = remember(filePath) {
        val url = if (filePath.startsWith("file://")) {
            NSURL.URLWithString(filePath)!!
        } else {
            NSURL.fileURLWithPath(filePath)
        }
        AVPlayer(uRL = url)
    }

    LaunchedEffect(filePath) { onFirstFrameRendered() }

    DisposableEffect(filePath) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = player.currentItem,
            queue = null,
        ) {
            player.seekToTime(CMTimeMake(0, 1))
            player.pause()
        }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            player.pause()
        }
    }

    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                this.player = player
                player.play()
            }
        },
        modifier = modifier,
    )
}

@Composable
actual fun TrimmableVideoPlayerSurface(
    filePath: String,
    clipStartMs: Long,
    clipEndMs: Long,
    isPlaying: Boolean,
    seekRequestMs: Long?,
    onPositionMs: (Long) -> Unit,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit,
) {
    val onPositionMsState = rememberUpdatedState(onPositionMs)

    val player = remember(filePath) {
        val url = if (filePath.startsWith("file://")) {
            NSURL.URLWithString(filePath)!!
        } else {
            NSURL.fileURLWithPath(filePath)
        }
        val asset = AVURLAsset.URLAssetWithURL(url, null)
        val item = AVPlayerItem(asset)
        AVPlayer(playerItem = item)
    }

    LaunchedEffect(filePath) { onFirstFrameRendered() }

    // Apply external play/pause
    LaunchedEffect(isPlaying) {
        if (isPlaying) player.play() else player.pause()
    }

    // External seek requests
    LaunchedEffect(seekRequestMs) {
        seekRequestMs?.let { ms ->
            player.seekToTime(CMTimeMake(ms, 1000))
        }
    }

    // Loop within [clipStartMs, clipEndMs] via the periodic observer.
    DisposableEffect(filePath, clipStartMs, clipEndMs) {
        val endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = player.currentItem,
            queue = null,
        ) {
            player.seekToTime(CMTimeMake(clipStartMs, 1000))
            if (isPlaying) player.play() else player.pause()
        }

        // ~30 Hz position observer that also enforces clip end.
        val timeObserver = player.addPeriodicTimeObserverForInterval(
            interval = CMTimeMake(33, 1000),
            queue = null,
        ) { _ ->
            val secs = CMTimeGetSeconds(player.currentTime())
            val ms = if (secs.isNaN()) 0L else (secs * 1000.0).toLong()
            if (ms >= clipEndMs) {
                player.seekToTime(CMTimeMake(clipStartMs, 1000))
                onPositionMsState.value(clipStartMs)
            } else {
                onPositionMsState.value(ms)
            }
        }

        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(endObserver)
            player.removeTimeObserver(timeObserver)
            player.pause()
        }
    }

    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                this.player = player
                this.showsPlaybackControls = false
            }
        },
        modifier = modifier,
    )
}
