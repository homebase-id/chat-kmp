package id.homebase.chat.widget.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import co.touchlab.kermit.Logger
import id.homebase.chat.widget.VideoDebugState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSURL

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoPlayer(
    videoUrl: String?,
    modifier: Modifier,
    onDebugUpdate: (VideoDebugState) -> Unit
) {
    Logger.i("VideoPlayer.iOS") { "VideoPlayer with URL: $videoUrl" }

    // 1. Audio Session Configuration
    // Best Practice: Ensure video audio plays even if hardware Silent Switch is ON.
    LaunchedEffect(Unit) {
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        } catch (e: Exception) {
            Logger.e("VideoPlayer.iOS") { "Failed to set audio session: ${e.message}" }
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Handle null URL (loading state)
        if (videoUrl == null) {
            CircularProgressIndicator()
            return@Box
        }

        // Handle empty URL
        if (videoUrl.isEmpty()) {
            Text(
                text = "No URL",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Box
        }

        // 2. Create player and controller together to ensure they stay linked
        val playerStack = remember(videoUrl) {
            val url = NSURL.URLWithString(videoUrl)
            if (url == null) {
                Logger.e("VideoPlayer.iOS") { "Invalid URL: $videoUrl" }
                null
            } else {
                val player = AVPlayer.playerWithURL(url)
                val vc = AVPlayerViewController().apply {
                    this.player = player
                    this.showsPlaybackControls = true
                }
                Pair(player, vc)
            }
        }

        // 3. Lifecycle Management
        DisposableEffect(playerStack) {
            val player = playerStack?.first
            val vc = playerStack?.second

            player?.play()

            onDispose {
                Logger.d("VideoPlayer.iOS") { "Stopping playback and cleaning up" }
                player?.pause()
                // Important: Break the retention cycle between VC and Player
                vc?.player = null
            }
        }

        // 4. Render
        if (playerStack != null) {
            UIKitView(
                factory = {
                    playerStack.second.view
                },
                modifier = Modifier.fillMaxSize(),
                update = { }
            )
        } else {
            Text("Invalid URL", color = MaterialTheme.colorScheme.error)
        }
    }
}