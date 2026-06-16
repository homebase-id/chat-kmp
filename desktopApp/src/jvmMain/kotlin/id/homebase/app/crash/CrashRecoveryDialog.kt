package id.homebase.app.crash

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Native Swing crash recovery dialog (no Compose/Skia). Blocks on the EDT. */
object CrashRecoveryDialog {

    private val SURFACE = Color(0xFB, 0xFC, 0xFF)
    private val SURFACE_VARIANT = Color(0xE7, 0xEB, 0xF3)
    private val ON_SURFACE_VARIANT = Color(0x54, 0x58, 0x63)
    private val PRIMARY = Color(0x2C, 0x58, 0xC3)
    private val ON_PRIMARY = Color.WHITE
    private val SECONDARY_CONTAINER = Color(0xDC, 0xE5, 0xF9)
    private val ON_SECONDARY_CONTAINER = Color(0x15, 0x1D, 0x2C)

    private const val MESSAGE_HTML =
        "<html><body style='width:420px;font-family:sans-serif'>" +
        "<h2 style='color:#2C58C3'>Well, this is awkward.</h2>" +
        "<p style='color:#1B1B1D'>Homebase tripped over its own feet and had to sit down for a second. " +
        "You really shouldn't be seeing this — but here we are.</p>" +
        "<p style='color:#545863'>Your stuff is safe. Sharing the report helps us fix this.</p>" +
        "</body></html>"

    fun showAndBlock(reportPath: String?) {
        try {
            val r = Runnable { buildAndShow(reportPath) }
            if (SwingUtilities.isEventDispatchThread()) r.run() else SwingUtilities.invokeAndWait(r)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        exitProcess(1)
    }

    /** Rounded (pill) button; [fill] null = a text button that only tints on hover. */
    private fun pillButton(text: String, fill: Color?, fg: Color): JButton =
        object : JButton(text) {
            init {
                isContentAreaFilled = false
                isFocusPainted = false
                isBorderPainted = false
                isOpaque = false
                foreground = fg
                font = font.deriveFont(Font.BOLD, 13f)
                border = BorderFactory.createEmptyBorder(11, 22, 11, 22)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = height
                when {
                    fill != null -> {
                        g2.color = if (model.isPressed) fill.darker() else fill
                        g2.fillRoundRect(0, 0, width, height, arc, arc)
                    }
                    model.isRollover -> {
                        g2.color = Color(fg.red, fg.green, fg.blue, 22)
                        g2.fillRoundRect(0, 0, width, height, arc, arc)
                    }
                }
                g2.dispose()
                super.paintComponent(g)
            }
        }

    private fun buildAndShow(reportPath: String?) {
        val report = reportPath?.let { runCatching { File(it).readText() }.getOrNull() }
            ?: "(crash report unavailable)"

        val dialog = JDialog(null as java.awt.Frame?, "Homebase", true)
        val root = JPanel(BorderLayout(0, 16)).apply {
            background = SURFACE
            border = BorderFactory.createEmptyBorder(24, 28, 20, 28)
        }
        root.add(JLabel(MESSAGE_HTML).apply { isOpaque = true; background = SURFACE }, BorderLayout.NORTH)

        val area = JTextArea(report, 14, 56).apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            background = SURFACE_VARIANT; foreground = ON_SURFACE_VARIANT
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        val scroll = JScrollPane(area).apply {
            preferredSize = Dimension(560, 240)
            isVisible = false
            border = BorderFactory.createEmptyBorder()
        }
        root.add(scroll, BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { background = SURFACE }
        val details = pillButton("Technical details", null, PRIMARY).apply {
            addActionListener { scroll.isVisible = !scroll.isVisible; dialog.pack() }
        }
        val quit = pillButton("Quit", null, ON_SURFACE_VARIANT).apply {
            addActionListener { exitProcess(1) }
        }
        val reveal = pillButton("Reveal crash report", SECONDARY_CONTAINER, ON_SECONDARY_CONTAINER).apply {
            addActionListener { revealReport(reportPath) }
        }
        val restart = pillButton("Restart", PRIMARY, ON_PRIMARY).apply {
            addActionListener { relaunch(); exitProcess(0) }
        }
        buttons.add(details); buttons.add(quit); buttons.add(reveal); buttons.add(restart)
        root.add(buttons, BorderLayout.SOUTH)

        dialog.contentPane = root
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    private fun revealReport(reportPath: String?) {
        val path = reportPath ?: return
        runCatching {
            val parent = File(path).parentFile ?: return
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(parent)
        }
    }

    private fun relaunch() {
        runCatching {
            val cmd = ProcessHandle.current().info().commandLine().orElse(null) ?: return
            ProcessBuilder(cmd.split(" ").filter { it.isNotBlank() }).inheritIO().start()
        }
    }
}
