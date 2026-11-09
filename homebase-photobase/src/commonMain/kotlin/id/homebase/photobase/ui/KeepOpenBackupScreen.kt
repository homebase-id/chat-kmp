package id.homebase.photobase.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.KeepScreenOn
import id.homebase.resources.MR
import id.homebase.resources.photobase_keep_open_body
import id.homebase.resources.photobase_keep_open_done
import id.homebase.resources.photobase_keep_open_progress
import org.jetbrains.compose.resources.stringResource

/** For the first big backup: the screen stays on and dark, and the app keeps the foreground it needs. */
@Composable
fun KeepOpenBackupScreen(viewModel: BackupSettingsViewModel, onDone: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val status = uiState.status
    KeepScreenOn(active = true)
    LaunchedEffect(Unit) { viewModel.onAction(BackupSettingsUiAction.BackUpNow) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(MR.string.photobase_keep_open_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
            LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(MR.string.photobase_keep_open_progress, status.backedUpCount.toString(), status.totalCount.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
            TextButton(onClick = onDone) {
                Text(stringResource(MR.string.photobase_keep_open_done))
            }
        }
    }
}
