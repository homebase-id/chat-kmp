package id.homebase.core.ui.screens.filesync.onboarding

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.ui.screens.filesync.FilesyncUiAction
import id.homebase.core.ui.screens.filesync.FilesyncViewModel
import id.homebase.core.ui.screens.filesync.components.FilesyncDesktopAppCard
import id.homebase.resources.MR
import id.homebase.resources.filesync_check_failed
import id.homebase.resources.filesync_label
import id.homebase.resources.filesync_onboarding_body_1
import id.homebase.resources.filesync_onboarding_body_2
import id.homebase.resources.filesync_onboarding_dismiss
import id.homebase.resources.filesync_onboarding_setup
import id.homebase.resources.filesync_onboarding_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesyncOnboardingScreen(
    viewModel: FilesyncViewModel,
    setupInitiated: Boolean,
    checkFailed: Boolean,
    onDismiss: () -> Unit,
) {
    // The grant is checked silently on open; the dialog only appears once the user asks to set up.
    if (setupInitiated) ExtendPermissionDialog(viewModel = viewModel.extendPermissionViewModel)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onAction(FilesyncUiAction.Paused) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAction(FilesyncUiAction.Resumed) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(MR.string.filesync_label)) }) },
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
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(MR.string.filesync_onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(MR.string.filesync_onboarding_body_1),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(MR.string.filesync_onboarding_body_2),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(40.dp))
            if (checkFailed) {
                Text(
                    text = stringResource(MR.string.filesync_check_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            FilledTonalButton(
                onClick = { viewModel.onAction(FilesyncUiAction.SetupClicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.string.filesync_onboarding_setup))
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.string.filesync_onboarding_dismiss))
            }
            Spacer(modifier = Modifier.height(32.dp))
            FilesyncDesktopAppCard()
        }
    }
}
