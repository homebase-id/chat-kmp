package id.homebase.chat.calling

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/** What the surface hosting the call offers; the default is the in-app overlay. */
@Immutable
data class CallHostCapabilities(
    val ownsWindow: Boolean = false,
    val macShortcuts: Boolean = false,
    /** Rounds the ring and mini content where the host window can show it (a transparent macOS frame). */
    val floatingWindowShape: Shape = RectangleShape,
)

val LocalCallHost = staticCompositionLocalOf { CallHostCapabilities() }

internal enum class CallTier { Embedded, Minimal, Compact, Medium, Expanded }

/** [compactHeight] is a phone on its side: too short for the stacked layout, and its width must not pass it off as a desktop. */
@Immutable
internal data class CallLayoutMetrics(val tier: CallTier = CallTier.Embedded, val compactHeight: Boolean = false) {
    val windowed: Boolean get() = tier != CallTier.Embedded
}

internal val LocalCallLayout = staticCompositionLocalOf { CallLayoutMetrics() }
