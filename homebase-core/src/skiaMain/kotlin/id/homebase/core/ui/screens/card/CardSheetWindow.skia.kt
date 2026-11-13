package id.homebase.core.ui.screens.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties

internal actual fun cardSheetDialogProperties(): DialogProperties = DialogProperties(
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
    scrimColor = Color.Transparent,
)

@Composable
internal actual fun CardSheetWindowEffect() = Unit
