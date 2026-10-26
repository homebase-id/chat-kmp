package id.homebase.core.ui.screens.email.auth

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import id.homebase.core.email.EmailPreferences
import id.homebase.core.ui.screens.vault.components.BiometricLockedContent
import id.homebase.core.ui.screens.auth.rememberBiometricUnlock
import id.homebase.core.ui.screens.auth.rememberStopPrivacyOverlay
import id.homebase.core.vault.needsComposePrivacyOverlay
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

    val unlock = rememberBiometricUnlock(
        initiallyAuthorized = !emailPreferences.requiresUnlock(),
        title = title,
        subtitle = subtitle,
        onAuthenticated = emailPreferences::recordAuthSuccess,
    )
    val biometricsEnabled by emailPreferences.biometricsEnabled.collectAsStateWithLifecycle()
    val privacyOverlayVisible = rememberStopPrivacyOverlay(biometricsEnabled && needsComposePrivacyOverlay)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && emailPreferences.requiresUnlock()) {
                unlock.lock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val lockedTitle = stringResource(MR.string.email_locked_title)
    val unlockLabel = stringResource(MR.string.email_locked_unlock)
    if (unlock.authorized) content() else BiometricLockedContent(
        title = lockedTitle,
        subtitle = subtitle,
        unlockLabel = unlockLabel,
        onUnlock = unlock::requestUnlock,
        unlockEnabled = !unlock.isAuthenticating,
    )

    if (privacyOverlayVisible) {
        BiometricLockedContent(
            title = lockedTitle,
            subtitle = subtitle,
            unlockLabel = unlockLabel,
            onUnlock = {},
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        )
    }
}
