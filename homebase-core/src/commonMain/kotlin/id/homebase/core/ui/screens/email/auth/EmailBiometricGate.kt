package id.homebase.core.ui.screens.email.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.homebase.core.email.EmailPreferences
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
        when (authenticateBiometric(title, subtitle)) {
            BiometricResult.Success, BiometricResult.Unavailable -> {
                emailPreferences.recordAuthSuccess()
                authorized = true
            }
            BiometricResult.Failure -> Unit
        }
        isAuthenticating = false
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

    if (authorized) content() else EmailLockedContent(onUnlock = { unlockAttempt++ })
}

@Composable
private fun EmailLockedContent(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(MR.string.email_locked_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(MR.string.email_biometric_prompt_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onUnlock) {
            Text(stringResource(MR.string.email_locked_unlock))
        }
    }
}
