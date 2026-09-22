package id.homebase.core.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

private val EastWestResize = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

actual fun Modifier.horizontalResizeCursor(): Modifier = pointerHoverIcon(EastWestResize)
