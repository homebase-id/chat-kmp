package id.homebase.core.ui.screens.filesync

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEvent
import java.awt.Toolkit
import java.awt.event.MouseEvent

// Compose's isBackPressed means AWT button 4, but X11's AWT gives 4 and 5 to the sideways wheel and the side buttons 6 and 7.
private val backButton = if (Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit") 6 else 4

internal actual fun PointerEvent.isMouseBackPress(): Boolean {
    val awt = awtEventOrNull ?: return false
    return awt.id == MouseEvent.MOUSE_PRESSED && awt.button == backButton
}
