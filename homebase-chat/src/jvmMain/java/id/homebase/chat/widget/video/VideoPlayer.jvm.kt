package id.homebase.chat.widget.video

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import javax.swing.JPanel
import java.awt.BorderLayout
import javafx.scene.Scene
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
@Composable
actual fun VideoPlayer(
    videoUrl: String?,
    modifier: Modifier
) {
    val jfxPanel = remember { JFXPanel() }

    LaunchedEffect(videoUrl) {
        if (videoUrl == null) return@LaunchedEffect

        Platform.runLater {
            val media = javafx.scene.media.Media(videoUrl)
            val player = javafx.scene.media.MediaPlayer(media)
            val view = javafx.scene.media.MediaView(player)
            jfxPanel.scene = Scene(javafx.scene.Group(view))
            player.play()
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = { JPanel(BorderLayout()).apply { add(jfxPanel) } }
    )
}

