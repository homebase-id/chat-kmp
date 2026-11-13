package id.homebase.core.ui.screens.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * The card sheet's dialog window: full width, edge to edge, no platform scrim or dim and no dismissal of its own,
 * so the sheet draws the only scrim and handles back and outside taps itself. Same on every platform.
 */
internal expect fun cardSheetDialogProperties(): DialogProperties

/** Removes window dim and window animation the platform adds to a dialog; does nothing where there is none. */
@Composable
internal expect fun CardSheetWindowEffect()
