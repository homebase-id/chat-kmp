package id.homebase.chat.widget.video

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import id.homebase.api.video.LocalVideoServer
import id.homebase.chat.widget.VideoDebugState
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javax.swing.JPanel
import java.awt.BorderLayout

/* -------------------------------------------------
   JVM-wide holder (lives for app lifetime)
-------------------------------------------------- */

private object DesktopVideoServerHolder {
    val server: LocalVideoServer by lazy {
        LocalVideoServer().also {
            kotlinx.coroutines.runBlocking {
                it.start()
            }
        }
    }
}

/* -------------------------------------------------
   VideoPlayer (JVM actual)
-------------------------------------------------- */

@Composable
actual fun VideoPlayer(
    videoUrl: String?,
    modifier: Modifier,
    onDebugUpdate: (VideoDebugState) -> Unit
) {
    // Force JavaFX init once
    remember { JFXPanel() }
    val server = remember { DesktopVideoServerHolder.server }

    val jfxPanel = remember { JFXPanel() }
    val mediaPlayer = remember { mutableStateOf<javafx.scene.media.MediaPlayer?>(null) }

    LaunchedEffect(videoUrl) {
        if (videoUrl == null) return@LaunchedEffect

        Platform.runLater {
            val media = javafx.scene.media.Media(videoUrl)
            media.setOnError {
                onDebugUpdate(
                    VideoDebugState(
                        status = "MEDIA_ERROR",
                        error = media.error?.message
                    )
                )
            }

            val player = javafx.scene.media.MediaPlayer(media)
            mediaPlayer.value = player

            val view = javafx.scene.media.MediaView(player)

            // ---- DEBUG HOOKS ----
            player.setOnReady {
                onDebugUpdate(VideoDebugState(status = "READY"))
            }

            player.statusProperty().addListener { _, _, s ->
                onDebugUpdate(VideoDebugState(status = s.toString()))
            }

            player.bufferProgressTimeProperty().addListener { _, _, t ->
                onDebugUpdate(
                    VideoDebugState(
                        status = player.status.toString(),
                        buffered = t.toString()
                    )
                )
            }

            player.setOnError {
                onDebugUpdate(
                    VideoDebugState(
                        status = "ERROR",
                        error = player.error?.message
                    )
                )
            }

            // ---- ATTACH + PLAY ----
            jfxPanel.scene = Scene(javafx.scene.Group(view))
            player.play()
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            JPanel(BorderLayout()).apply {
                add(jfxPanel, BorderLayout.CENTER)
            }
        }
    )
}
