@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.seekToTime
import platform.AVKit.AVPlayerViewController
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
