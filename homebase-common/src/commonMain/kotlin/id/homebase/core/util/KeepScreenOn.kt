package id.homebase.core.util

import androidx.compose.runtime.Composable

/** Keeps the screen awake while [active]; reference-counted, so one holder's release can't drop another's. */
@Composable
expect fun KeepScreenOn(active: Boolean)
