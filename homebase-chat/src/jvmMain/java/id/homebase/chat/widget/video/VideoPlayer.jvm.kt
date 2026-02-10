package id.homebase.chat.widget.video

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import id.homebase.chat.widget.VideoDebugState
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.awt.BorderLayout
import javax.swing.JPanel

@Composable
actual fun VideoPlayer(
    videoUrl: String?,
    modifier: Modifier,
    onDebugUpdate: (VideoDebugState) -> Unit
) {
    val mediaPlayerFactory = remember { MediaPlayerFactory() }
    val mediaPlayer = remember {
        mediaPlayerFactory.mediaPlayers().newEmbeddedMediaPlayer()
    }

    // 🔑 Track surface attachment ourselves
    val surfaceAttached = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer.controls().stop()
            mediaPlayer.release()
            mediaPlayerFactory.release()
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            JPanel(BorderLayout()).apply {
                val canvas = java.awt.Canvas().apply {
                    background = java.awt.Color.BLACK
                }
                add(canvas, BorderLayout.CENTER)
                putClientProperty("vlc-canvas", canvas)
            }
        },
        update = { panel ->
            val canvas = panel.getClientProperty("vlc-canvas") as java.awt.Canvas

            // Attach surface exactly once
            if (!surfaceAttached.value) {
                val surface =
                    mediaPlayerFactory.videoSurfaces().newVideoSurface(canvas)
                mediaPlayer.videoSurface().set(surface)
                surfaceAttached.value = true
            }

            // Start playback only after surface exists
            if (videoUrl != null && surfaceAttached.value && !mediaPlayer.status().isPlaying) {
                mediaPlayer.media().play(videoUrl)
                onDebugUpdate(VideoDebugState(status = "PLAYING"))
            }
        }
    )
}
