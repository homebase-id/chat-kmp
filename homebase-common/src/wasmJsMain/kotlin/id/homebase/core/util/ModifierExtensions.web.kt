package id.homebase.core.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.fromKeyword
import androidx.compose.ui.input.pointer.pointerHoverIcon

@OptIn(ExperimentalComposeUiApi::class)
private val EastWestResize = PointerIcon.fromKeyword("ew-resize")

actual fun Modifier.horizontalResizeCursor(): Modifier = pointerHoverIcon(EastWestResize)
