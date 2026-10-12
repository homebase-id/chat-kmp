package id.homebase.chat.services.calling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

actual fun platformCallEngineFactory(): CallEngineFactory? = CallEngineFactory { WebRtcJavaCallEngine(it) }

@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier) {
    val video = handle as? JvmVideoHandle ?: return
    val frame = video.frame.collectAsState().value ?: return
    Canvas(modifier.clipToBounds().fillMaxSize()) {
        val fill = maxOf(size.width / frame.width, size.height / frame.height)
        val drawn = Size(frame.width * fill, frame.height * fill)
        val dx = ((size.width - drawn.width) / 2).toInt()
        val dy = ((size.height - drawn.height) / 2).toInt()
        scale(if (video.mirrored) -1f else 1f, 1f) {
            drawImage(
                image = frame,
                srcSize = IntSize(frame.width, frame.height),
                dstOffset = IntOffset(dx, dy),
                dstSize = IntSize(drawn.width.toInt(), drawn.height.toInt()),
            )
        }
    }
}

@Composable
actual fun CallRingEffect(ringing: Boolean, callerName: String) {
    androidx.compose.runtime.LaunchedEffect(ringing, callerName) {
        if (!ringing) return@LaunchedEffect
        val tray = runCatching { if (java.awt.SystemTray.isSupported()) java.awt.SystemTray.getSystemTray() else null }.getOrNull()
        val icon = tray?.let {
            java.awt.TrayIcon(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), "Homebase").also { icon ->
                icon.isImageAutoSize = true
                runCatching { it.add(icon) }
            }
        }
        try {
            icon?.displayMessage(callerName, "Incoming call", java.awt.TrayIcon.MessageType.INFO)
            while (true) {
                java.awt.Toolkit.getDefaultToolkit().beep()
                kotlinx.coroutines.delay(2_000)
            }
        } finally {
            if (icon != null) runCatching { tray.remove(icon) }
        }
    }
}

actual fun platformAudioRoutes(): List<AudioRoute> = emptyList()
