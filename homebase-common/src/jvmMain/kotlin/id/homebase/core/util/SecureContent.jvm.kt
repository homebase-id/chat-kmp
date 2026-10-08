package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
actual fun SecureWindowEffect(active: Boolean) = Unit

@Composable
actual fun rememberScreenCaptureObserver(onScreenshot: () -> Unit): State<Boolean> = remember { mutableStateOf(false) }
