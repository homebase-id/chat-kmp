package id.homebase.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

interface HardwareShortcutDetector {
    fun detectKey(event: KeyEvent): ShortcutEvent?

    companion object {
        val Default: HardwareShortcutDetector = DefaultHardwareShortcutDetector
    }
}

sealed interface ShortcutEvent {
    data class Zoom(
        val direction: ZoomDirection,
        val factor: Float = 0.2f,
        val centroid: Offset = Offset.Unspecified,
    ) : ShortcutEvent

    data class Pan(
        val direction: PanDirection,
        val amount: Dp = 50.dp,
    ) : ShortcutEvent
}

enum class ZoomDirection { In, Out }
enum class PanDirection { Up, Down, Left, Right }

internal object DefaultHardwareShortcutDetector : HardwareShortcutDetector {
    override fun detectKey(event: KeyEvent): ShortcutEvent? {
        if (event.type != KeyEventType.KeyDown) return null
        val isModifier = event.isCtrlPressed || event.isMetaPressed
        return when {
            isModifier && event.key == Key.Equals -> ShortcutEvent.Zoom(ZoomDirection.In)
            isModifier && event.key == Key.Minus -> ShortcutEvent.Zoom(ZoomDirection.Out)
            event.key == Key.DirectionUp -> ShortcutEvent.Pan(PanDirection.Up)
            event.key == Key.DirectionDown -> ShortcutEvent.Pan(PanDirection.Down)
            event.key == Key.DirectionLeft -> ShortcutEvent.Pan(PanDirection.Left)
            event.key == Key.DirectionRight -> ShortcutEvent.Pan(PanDirection.Right)
            else -> null
        }
    }
}
