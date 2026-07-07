package id.homebase.core.ui.theme

import androidx.compose.runtime.Composable

/**
 * Keeps the platform system chrome in sync with the app theme. Android re-applies
 * transparent edge-to-edge bars with icon contrast matching [darkTheme]; iOS
 * overrides the window's interface style so the status bar follows a forced
 * Light/Dark preference. [followsSystemTheme] must be true when the user picked
 * "System": iOS then clears the override — pinning a concrete style there would
 * freeze the trait collection and stop isSystemInDarkTheme() from tracking the
 * OS setting. Desktop/Web have no app-managed system chrome and no-op.
 */
@Composable
internal expect fun UpdateEdgeToEdge(darkTheme: Boolean, followsSystemTheme: Boolean)
