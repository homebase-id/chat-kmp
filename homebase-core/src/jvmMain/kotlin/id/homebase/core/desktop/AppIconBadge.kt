package id.homebase.core.desktop

import co.touchlab.kermit.Logger
import id.homebase.chat.services.convo.ConversationStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.awt.Color
import java.awt.EventQueue
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.Window
import java.awt.image.BufferedImage

private const val TAG = "AppIconBadge"
private const val OVERLAY_CAP = 99
private const val OVERLAY_PX = 32
private const val OVERLAY_TEXT_PX = 24
private const val OVERLAY_FONT_MAX_PT = 22
private const val OVERLAY_FONT_MIN_PT = 8

// The overlay is painted by AWT outside any composition, so MaterialTheme is unreachable here.
private val OVERLAY_BACKGROUND = Color(0xD3, 0x2F, 0x2F)

/**
 * Total unread count on the app's task-area icon: a text badge on the macOS Dock (and on a
 * Unity launcher), an overlay image on the Windows taskbar button. Desktops that report
 * neither feature are left untouched.
 */
object AppIconBadge {

    @Volatile
    private var window: Window? = null

    @Volatile
    private var lastCount: Int = 0

    private var collecting = false
    private var loggedSupport = false

    /** Call once the Compose window exists; the Windows overlay is per-window. */
    fun start(window: Window) {
        this.window = window
        if (collecting) {
            apply(lastCount)
            return
        }
        collecting = true
        val koin = GlobalContext.get()
        koin.get<CoroutineScope>().launch {
            koin.get<ConversationStream>().totalUnreadCount.collect { count ->
                lastCount = count
                apply(count)
            }
        }
    }

    private fun apply(unreadCount: Int) {
        val taskbar = if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar() else return
        EventQueue.invokeLater {
            val supportsText = taskbar.isSupported(Taskbar.Feature.ICON_BADGE_NUMBER)
            val supportsOverlay = taskbar.isSupported(Taskbar.Feature.ICON_BADGE_IMAGE_WINDOW)
            if (!loggedSupport) {
                loggedSupport = true
                Logger.i(tag = TAG) {
                    "taskbar badge support: text=$supportsText overlay=$supportsOverlay"
                }
            }
            if (supportsText) taskbar.setIconBadge(dockBadgeText(unreadCount))
            val frame = window
            if (supportsOverlay && frame != null) {
                taskbar.setWindowIconBadge(
                    frame,
                    overlayBadgeText(unreadCount)?.let(::renderOverlay),
                )
            }
        }
    }
}

internal fun dockBadgeText(unreadCount: Int): String? =
    if (unreadCount <= 0) null else unreadCount.toString()

internal fun overlayBadgeText(unreadCount: Int): String? = when {
    unreadCount <= 0 -> null
    unreadCount > OVERLAY_CAP -> "$OVERLAY_CAP+"
    else -> unreadCount.toString()
}

private fun renderOverlay(text: String): Image {
    val image = BufferedImage(OVERLAY_PX, OVERLAY_PX, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON,
        )
        graphics.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        graphics.color = OVERLAY_BACKGROUND
        graphics.fillOval(0, 0, OVERLAY_PX, OVERLAY_PX)
        graphics.color = Color.WHITE
        graphics.font = fittedFont(graphics, text)
        val metrics = graphics.fontMetrics
        graphics.drawString(
            text,
            (OVERLAY_PX - metrics.stringWidth(text)) / 2,
            (OVERLAY_PX - metrics.height) / 2 + metrics.ascent,
        )
    } finally {
        graphics.dispose()
    }
    return image
}

// Windows and macOS resolve SANS_SERIF to different faces, so the size has to be measured
// against the real metrics rather than tabulated per digit count.
private fun fittedFont(graphics: Graphics2D, text: String): Font {
    for (points in OVERLAY_FONT_MAX_PT downTo OVERLAY_FONT_MIN_PT) {
        val font = Font(Font.SANS_SERIF, Font.BOLD, points)
        val metrics = graphics.getFontMetrics(font)
        if (metrics.stringWidth(text) <= OVERLAY_TEXT_PX &&
            metrics.ascent + metrics.descent <= OVERLAY_TEXT_PX
        ) {
            return font
        }
    }
    return Font(Font.SANS_SERIF, Font.BOLD, OVERLAY_FONT_MIN_PT)
}
