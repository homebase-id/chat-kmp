package id.homebase.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import id.homebase.resources.MR
import id.homebase.resources.homebase_icon_mono
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ComposeNativeTray 1.3.3 renders composable icons with a skiko method that no longer exists, so we hand it files.
internal class TrayIconFiles(val pngPath: String, val icoPath: String)

private const val TRAY_ICON_PX = 32

internal fun renderTrayIconPng(darkBar: Boolean, callIndicator: Boolean): ByteArray {
    val scene = ImageComposeScene(TRAY_ICON_PX, TRAY_ICON_PX, Density(1f)) {
        Box(Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(MR.drawable.homebase_icon_mono),
                contentDescription = null,
                tint = if (darkBar) Color.White else Color.Black,
                modifier = Modifier.fillMaxSize(),
            )
            if (callIndicator) {
                Box(Modifier.align(Alignment.TopEnd).fillMaxSize(0.38f).background(CALL_INDICATOR, CircleShape))
            }
        }
    }
    try {
        return scene.render().use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "tray icon PNG encode failed" }.bytes
        }
    } finally {
        scene.close()
    }
}

internal fun pngToIco(png: ByteArray, sizePx: Int = TRAY_ICON_PX): ByteArray {
    val headerSize = 6 + 16
    return ByteBuffer.allocate(headerSize + png.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0)
        putShort(1)
        putShort(1)
        put(sizePx.toByte())
        put(sizePx.toByte())
        put(0)
        put(0)
        putShort(1)
        putShort(32)
        putInt(png.size)
        putInt(headerSize)
        put(png)
    }.array()
}

internal fun writeTrayIconFiles(darkBar: Boolean, callIndicator: Boolean, dir: File = trayIconDir()): TrayIconFiles {
    dir.mkdirs()
    val name = "tray-${if (darkBar) "dark" else "light"}${if (callIndicator) "-call" else ""}"
    val png = renderTrayIconPng(darkBar, callIndicator)
    val pngFile = File(dir, "$name.png").apply { writeBytes(png) }
    val icoFile = File(dir, "$name.ico").apply { writeBytes(pngToIco(png)) }
    return TrayIconFiles(pngFile.trayPath(), icoFile.trayPath())
}

private fun trayIconDir() = File(System.getProperty("java.io.tmpdir"), "homebase-tray")

// The tray URL-decodes then URI-parses this string, so a raw Windows path or a '+'/'%' in it breaks the lookup.
internal fun File.trayPath(): String = absoluteFile.toURI().toString().replace("%", "%25").replace("+", "%2B")

// The menu bar is drawn by the OS outside MaterialTheme, so the recording-red dot and the white/black glyph follow the system bar, not our scheme.
private val CALL_INDICATOR = Color(0xFFE53935)
