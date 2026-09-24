package id.homebase.core.settings

import androidx.compose.runtime.Composable

@Composable
fun rememberMirrorFrontCamera(): Boolean = rememberPreference { it.mirrorFrontCamera }
