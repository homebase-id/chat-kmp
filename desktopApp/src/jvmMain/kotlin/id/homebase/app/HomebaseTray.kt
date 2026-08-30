package id.homebase.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ApplicationScope
import co.touchlab.kermit.Logger
import com.kdroid.composetray.lib.mac.MacOSMenuBarThemeDetector
import com.kdroid.composetray.menu.api.TrayMenuBuilder
import com.kdroid.composetray.tray.api.Tray
import com.kdroid.composetray.utils.isMenuBarInDarkMode
import id.homebase.resources.MR
import id.homebase.resources.homebase_icon_mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import java.awt.AlphaComposite
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import javax.imageio.ImageIO

@Composable
fun ApplicationScope.HomebaseTray(
    tooltip: String,
    primaryAction: () -> Unit,
    menuContent: TrayMenuBuilder.() -> Unit,
) {
    if (isMacOs) {
        MacMenuBarTray(tooltip, primaryAction, menuContent)
    } else {
        Tray(
            // The menu bar follows the desktop picture, not the app theme, so this tints off
            // the bar's own appearance rather than a MaterialTheme role.
            iconContent = {
                Icon(
                    painter = painterResource(MR.drawable.homebase_icon_mono),
                    contentDescription = tooltip,
                    tint = if (isMenuBarInDarkMode()) Color.White else Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            tooltip = tooltip,
            primaryAction = primaryAction,
            menuContent = menuContent,
        )
    }
}

// The composable-icon Tray overloads resolve the menu bar appearance during composition, i.e.
// on the EDT, and on macOS that blocks on the AppKit main queue. If AppKit is at that moment
// calling into AWT for an accessibility query it is already waiting on the EDT, and the two
// deadlock — an unrecoverable startup hang. The deprecated path-based overload is the only
// one that never touches the detector, so feed it an icon rendered off the EDT.
@Suppress("DEPRECATION")
@Composable
private fun ApplicationScope.MacMenuBarTray(
    tooltip: String,
    primaryAction: () -> Unit,
    menuContent: TrayMenuBuilder.() -> Unit,
) {
    val iconPath = rememberMenuBarIconPath()
    if (iconPath.isEmpty()) return
    Tray(
        iconPath = iconPath,
        tooltip = tooltip,
        primaryAction = primaryAction,
        menuContent = menuContent,
    )
}

@Composable
private fun rememberMenuBarIconPath(): String {
    var dark by remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(Unit) {
        val listener = Consumer<Boolean> { dark = it }
        // registerListener answers inline with the current value, so it blocks the same way.
        val seed =
            Thread({
                runCatching { MacOSMenuBarThemeDetector.registerListener(listener) }
                    .onFailure {
                        Logger.w(throwable = it, tag = "HomebaseTray") {
                            "Menu bar theme detector unavailable; assuming a dark menu bar"
                        }
                        dark = true
                    }
            }, "TrayIconThemeSeed")
        seed.isDaemon = true
        seed.start()
        onDispose { MacOSMenuBarThemeDetector.removeListener(listener) }
    }

    var path by remember { mutableStateOf("") }
    val currentDark = dark
    LaunchedEffect(currentDark) {
        if (currentDark == null) return@LaunchedEffect
        path = withContext(Dispatchers.IO) { menuBarIconPath(currentDark) }
    }
    return path
}

private const val MENU_BAR_ICON_PX = 44

private val menuBarIcons = ConcurrentHashMap<Boolean, String>()

private suspend fun menuBarIconPath(dark: Boolean): String {
    menuBarIcons[dark]?.let { return it }
    val source = MR.readBytes("drawable/homebase_icon_mono.png")
    val file =
        File.createTempFile(if (dark) "homebase-tray-dark" else "homebase-tray-light", ".png")
            .apply { deleteOnExit() }
    file.writeBytes(tintMonochrome(source, dark))
    return file.absolutePath.also { menuBarIcons[dark] = it }
}

private fun tintMonochrome(
    sourcePng: ByteArray,
    dark: Boolean,
): ByteArray {
    val source = ImageIO.read(ByteArrayInputStream(sourcePng))
    val scaled = source.getScaledInstance(MENU_BAR_ICON_PX, MENU_BAR_ICON_PX, Image.SCALE_SMOOTH)
    val tinted = BufferedImage(MENU_BAR_ICON_PX, MENU_BAR_ICON_PX, BufferedImage.TYPE_INT_ARGB)
    tinted.createGraphics().apply {
        drawImage(scaled, 0, 0, null)
        composite = AlphaComposite.SrcIn
        color = if (dark) java.awt.Color.WHITE else java.awt.Color.BLACK
        fillRect(0, 0, MENU_BAR_ICON_PX, MENU_BAR_ICON_PX)
        dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(tinted, "png", it) }.toByteArray()
}
