package id.homebase.core.ui.screens.email.secrets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.screens.email.model.EmailCredential
import id.homebase.core.ui.screens.email.model.EmailKeyRef
import id.homebase.resources.MR
import id.homebase.resources.email_secrets_current_key
import id.homebase.resources.email_secrets_hide
import id.homebase.resources.email_secrets_keys
import id.homebase.resources.email_secrets_no_delete_note
import id.homebase.resources.email_secrets_password_warning
import id.homebase.resources.email_secrets_passwords
import id.homebase.resources.email_secrets_retired_key
import id.homebase.resources.email_secrets_reveal
import id.homebase.resources.email_secrets_revoke
import id.homebase.resources.email_secrets_title
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun EmailSecretsScreen(
    viewModel: EmailSecretsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    EmailSecretsUi(uiState = uiState, onAction = viewModel::onAction, onBackClick = onBackClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailSecretsUi(
    uiState: EmailSecretsUiState,
    onAction: (EmailSecretsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.email_secrets_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader(stringResource(MR.string.email_secrets_passwords))

            Text(
                text = stringResource(MR.string.email_secrets_password_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            uiState.credentials.forEach { credential ->
                CredentialCard(
                    credential = credential,
                    revealed = credential.id in uiState.revealedIds,
                    busy = credential.id in uiState.busyIds,
                    onAction = onAction,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(stringResource(MR.string.email_secrets_keys))

            uiState.keys.forEach { key ->
                KeyCard(key = key, isCurrent = key.uniqueId == uiState.currentKeyFileId)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = stringResource(MR.string.email_secrets_no_delete_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            uiState.error?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = { onAction(EmailSecretsUiAction.ErrorDismissed) }) {
                            Text(stringResource(MR.string.email_secrets_hide))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun CredentialCard(
    credential: EmailCredential,
    revealed: Boolean,
    busy: Boolean,
    onAction: (EmailSecretsUiAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = credential.label, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                // Monospace because this gets typed into a mail client by hand.
                text = if (revealed) credential.secret else "•".repeat(credential.secret.length.coerceAtMost(24)),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                TextButton(onClick = { onAction(EmailSecretsUiAction.ToggleReveal(credential.id)) }) {
                    Text(
                        stringResource(
                            if (revealed) MR.string.email_secrets_hide else MR.string.email_secrets_reveal
                        )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { onAction(EmailSecretsUiAction.Revoke(credential)) }) {
                        Text(
                            text = stringResource(MR.string.email_secrets_revoke),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCard(key: EmailKeyRef, isCurrent: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    if (isCurrent) MR.string.email_secrets_current_key else MR.string.email_secrets_retired_key
                ),
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = key.displayFingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
