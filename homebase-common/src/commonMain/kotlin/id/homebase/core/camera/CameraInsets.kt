package id.homebase.core.camera

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

// safeDrawing includes the IME: the camera window inherits the chat composer's open keyboard and its dismiss animation.
internal val cameraSafeInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
