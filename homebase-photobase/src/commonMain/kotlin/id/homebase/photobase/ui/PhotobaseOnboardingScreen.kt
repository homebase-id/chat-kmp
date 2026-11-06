package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.resources.MR
import id.homebase.resources.photobase_check_failed
import id.homebase.resources.photobase_check_retry
import id.homebase.resources.photobase_label
import id.homebase.resources.photobase_onboarding_body_1
import id.homebase.resources.photobase_onboarding_body_2
import id.homebase.resources.photobase_onboarding_dismiss
import id.homebase.resources.photobase_onboarding_setup
import id.homebase.resources.photobase_onboarding_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun PhotobaseOnboardingScreen(viewModel: PhotobaseSetupViewModel, onDismiss: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The grant is checked silently on open; the dialog only appears once the user asks to set up.
    if (uiState.setupInitiated) ExtendPermissionDialog(viewModel = viewModel.extendPermissionViewModel)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onAction(PhotobaseSetupUiAction.Paused) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAction(PhotobaseSetupUiAction.Resumed) }
    DisposableEffect(viewModel) { onDispose { viewModel.onAction(PhotobaseSetupUiAction.SetupLeft) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(MR.string.photobase_label)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(MR.string.photobase_onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(MR.string.photobase_onboarding_body_1),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(MR.string.photobase_onboarding_body_2),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            if (uiState.checkFailed) {
                Text(
                    text = stringResource(MR.string.photobase_check_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
            }
            FilledTonalButton(
                onClick = { viewModel.onAction(PhotobaseSetupUiAction.SetupClicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.string.photobase_onboarding_setup))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(MR.string.photobase_onboarding_dismiss))
            }
        }
    }
}

@Composable
fun PhotobaseCheckingScreen(failed: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(MR.string.photobase_check_failed),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRetry) { Text(stringResource(MR.string.photobase_check_retry)) }
            }
        } else {
            CircularProgressIndicator()
        }
    }
}
