package id.homebase.core.ui.screens.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.homebase.core.vault.BiometricResult
import id.homebase.core.vault.authenticateBiometric

@Stable
class BiometricUnlock internal constructor(initiallyAuthorized: Boolean) {
    var authorized by mutableStateOf(initiallyAuthorized)
        internal set
    var isAuthenticating by mutableStateOf(false)
        internal set
    internal var attempt by mutableStateOf(0)

    fun requestUnlock() {
        if (!isAuthenticating) attempt++
    }

    fun lock() {
        authorized = false
    }
}

/** Prompts whenever the gate is locked and again on each [BiometricUnlock.requestUnlock]; [onAuthenticated] runs before the gate opens. */
@Composable
fun rememberBiometricUnlock(
    initiallyAuthorized: Boolean,
    title: String,
    subtitle: String,
    onAuthenticated: () -> Unit,
    authenticate: suspend (String, String) -> BiometricResult = ::authenticateBiometric,
): BiometricUnlock {
    val unlock = remember { BiometricUnlock(initiallyAuthorized) }
    LaunchedEffect(unlock.authorized, unlock.attempt) {
        if (unlock.authorized || unlock.isAuthenticating) return@LaunchedEffect
        unlock.isAuthenticating = true
        try {
            when (authenticate(title, subtitle)) {
                BiometricResult.Success, BiometricResult.Unavailable -> {
                    onAuthenticated()
                    unlock.authorized = true
                }
                BiometricResult.Failure -> Unit
            }
        } finally {
            unlock.isAuthenticating = false
        }
    }
    return unlock
}

/** True from ON_STOP until the next ON_RESUME while [enabled]; cover the content with it so the OS app-switcher snapshot shows no secrets. */
@Composable
fun rememberStopPrivacyOverlay(enabled: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (enabled) visible = true
                Lifecycle.Event.ON_RESUME -> visible = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return visible && enabled
}
