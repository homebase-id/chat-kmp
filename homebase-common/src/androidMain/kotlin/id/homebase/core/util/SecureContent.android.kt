package id.homebase.core.util

import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

private val secureHolds = WindowHolds(
    apply = { (attributes.flags and FLAG_SECURE != 0).also { addFlags(FLAG_SECURE) } },
    restore = { wasSecure -> if (!wasSecure) clearFlags(FLAG_SECURE) },
)

@Composable
actual fun SecureWindowEffect(active: Boolean) = WindowHoldEffect(secureHolds, active)

@Composable
actual fun rememberScreenCaptureObserver(onScreenshot: () -> Unit): State<Boolean> = remember { mutableStateOf(false) }
