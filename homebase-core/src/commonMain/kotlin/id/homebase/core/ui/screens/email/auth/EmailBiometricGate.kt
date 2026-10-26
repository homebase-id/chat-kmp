package id.homebase.core.ui.screens.email.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.homebase.core.email.EmailPreferences
import id.homebase.core.ui.screens.vault.components.BiometricLockedContent
import id.homebase.core.vault.BiometricResult
import id.homebase.core.vault.authenticateBiometric
import id.homebase.resources.MR
import id.homebase.resources.email_biometric_prompt_subtitle
import id.homebase.resources.email_biometric_prompt_title
import id.homebase.resources.email_locked_title
import id.homebase.resources.email_locked_unlock
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun EmailBiometricGate(
    emailPreferences: EmailPreferences = koinInject(),
    content: @Composable () -> Unit,
) {
    val title = stringResource(MR.string.email_biometric_prompt_title)
    val subtitle = stringResource(MR.string.email_biometric_prompt_subtitle)

    var authorized by remember { mutableStateOf(!emailPreferences.requiresUnlock()) }
    var unlockAttempt by remember { mutableStateOf(0) }
    var isAuthenticating by remember { mutableStateOf(false) }

    LaunchedEffect(authorized, unlockAttempt) {
        if (authorized || isAuthenticating) return@LaunchedEffect
        isAuthenticating = true
        try {
            when (authenticateBiometric(title, subtitle)) {
                BiometricResult.Success, BiometricResult.Unavailable -> {
                    emailPreferences.recordAuthSuccess()
                    authorized = true
                }
                BiometricResult.Failure -> Unit
            }
        } finally {
            isAuthenticating = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && emailPreferences.requiresUnlock()) {
                authorized = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (authorized) content() else BiometricLockedContent(
        title = stringResource(MR.string.email_locked_title),
        subtitle = subtitle,
        unlockLabel = stringResource(MR.string.email_locked_unlock),
        onUnlock = { unlockAttempt++ },
        unlockEnabled = !isAuthenticating,
    )
}
