package id.homebase.core.ui.screens.vault.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.ui.screens.auth.rememberBiometricUnlock
import id.homebase.core.ui.screens.auth.rememberStopPrivacyOverlay
import id.homebase.core.ui.screens.vault.components.VaultLockedContent
import id.homebase.core.vault.VaultPreferences
import id.homebase.core.vault.needsComposePrivacyOverlay
import id.homebase.resources.MR
import id.homebase.resources.vault_biometric_prompt_subtitle
import id.homebase.resources.vault_biometric_prompt_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultBiometricGate(
    vaultPreferences: VaultPreferences,
    vaultExtendPermissionViewModel: ExtendPermissionViewModel,
    onExpired: () -> Unit,
    isPickerActive: Boolean,
    onPickerHandled: () -> Unit,
    content: @Composable () -> Unit,
) {
    val biometricTitle = stringResource(MR.string.vault_biometric_prompt_title)
    val biometricSubtitle = stringResource(MR.string.vault_biometric_prompt_subtitle)

    val unlock = rememberBiometricUnlock(
        initiallyAuthorized = !vaultPreferences.biometricsEnabled.value || vaultPreferences.isAuthSessionValid(),
        title = biometricTitle,
        subtitle = biometricSubtitle,
        onAuthenticated = vaultPreferences::recordAuthSuccess,
    )

    val biometricsEnabled by vaultPreferences.biometricsEnabled.collectAsStateWithLifecycle()

    // NOTE: `isVaultScreenActive` (the foreground-active flag that suppresses the idle
    // auto-lock and drives the iOS privacy overlay) is owned by AppNavHost, which tracks
    // the whole Vault back stack — this gate is disposed when a sub-screen (e.g. the note
    // editor) is pushed on top, so it can't span the flow.

    val isPrivacyOverlayVisible = rememberStopPrivacyOverlay(biometricsEnabled && needsComposePrivacyOverlay)

    // Lifecycle observer: track backgrounding and recheck permissions on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasBeenBackgrounded by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // This observer is bound to the grid's NavBackStackEntry, so ON_STOP also
                    // fires when navigating to a Vault sub-screen — NOT only on a genuine
                    // app-background. recordAppBackgrounded() therefore lives in
                    // VaultSessionTracker (Activity lifecycle); here we only note that the
                    // grid was stopped so ON_RESUME re-checks the (already-correct) session.
                    if (!isPickerActive) {
                        hasBeenBackgrounded = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (isPickerActive) {
                        onPickerHandled()
                    } else {
                        vaultExtendPermissionViewModel.recheckPermissions()
                        if (hasBeenBackgrounded &&
                            vaultPreferences.biometricsEnabled.value &&
                            !vaultPreferences.isAuthSessionValid()
                        ) {
                            onExpired()
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ExtendPermissionDialog(viewModel = vaultExtendPermissionViewModel)

    val unlockFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val unlockScale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    AnimatedContent(
        targetState = unlock.authorized,
        transitionSpec = {
            // Locking must cover the content on its first frame, so only unlocking animates.
            if (targetState) {
                fadeIn(unlockFade) + scaleIn(unlockScale, initialScale = 0.96f) togetherWith fadeOut(unlockFade)
            } else {
                EnterTransition.None togetherWith ExitTransition.None
            }
        },
    ) { isAuthorized ->
        if (!isAuthorized) {
            VaultLockedContent(
                onUnlock = unlock::requestUnlock,
                unlockEnabled = !unlock.isAuthenticating,
            )
        } else {
            content()
        }
    }

    if (isPrivacyOverlayVisible) {
        VaultLockedContent(
            onUnlock = { },
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}
