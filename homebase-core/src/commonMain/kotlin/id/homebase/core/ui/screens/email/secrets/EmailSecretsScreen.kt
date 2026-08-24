package id.homebase.core.ui.screens.email.secrets

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.ui.screens.email.model.EmailCredential
import id.homebase.core.ui.screens.email.model.EmailKeyRef
import id.homebase.resources.MR
import id.homebase.resources.email_secrets_cancel
import id.homebase.resources.email_secrets_copy_fingerprint
import id.homebase.resources.email_secrets_copy_password
import id.homebase.resources.email_secrets_copy_private_key
import id.homebase.resources.email_secrets_copy_public_key
import id.homebase.resources.email_secrets_hide
import id.homebase.resources.email_secrets_key_current
import id.homebase.resources.email_secrets_key_retired
import id.homebase.resources.email_secrets_keys
import id.homebase.resources.email_secrets_new_key
import id.homebase.resources.email_secrets_new_key_body
import id.homebase.resources.email_secrets_new_key_confirm
import id.homebase.resources.email_secrets_new_key_title
import id.homebase.resources.email_secrets_no_delete_note
import id.homebase.resources.email_secrets_password_warning
import id.homebase.resources.email_secrets_passwords
import id.homebase.resources.email_secrets_private_key_body
import id.homebase.resources.email_secrets_private_key_confirm
import id.homebase.resources.email_secrets_private_key_title
import id.homebase.resources.email_secrets_reveal
import id.homebase.resources.email_secrets_revoke
import id.homebase.resources.email_secrets_revoke_body
import id.homebase.resources.email_secrets_revoke_confirm
import id.homebase.resources.email_secrets_revoke_title
import id.homebase.resources.email_secrets_title
import id.homebase.resources.menu_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun EmailSecretsScreen(
    viewModel: EmailSecretsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    EmailSecretsUi(uiState = uiState, onAction = viewModel::onAction, onBackClick = onBackClick)
}

/**
 * The identity's mail-client passwords and encryption keys.
 *
 * Two rules this screen learned the hard way: a destructive action must not sit where a
 * harmless one is expected, and a status must not look like a control. Revoking is irreversible
 * — it kills the credential on the mail server — so it is quiet, last, and asks first; revealing
 * and copying are the everyday actions and are the prominent ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailSecretsUi(
    uiState: EmailSecretsUiState,
    onAction: (EmailSecretsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    var confirmRevoke by remember { mutableStateOf<EmailCredential?>(null) }
    var confirmPrivateKey by remember { mutableStateOf<EmailKeyRef?>(null) }
    var confirmNewKey by remember { mutableStateOf(false) }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(clipEntryOf(text)) } }

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
                    onToggleReveal = { onAction(EmailSecretsUiAction.ToggleReveal(credential.id)) },
                    onCopy = { copy(credential.secret) },
                    onRevoke = { confirmRevoke = credential },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(stringResource(MR.string.email_secrets_keys))

            uiState.keys.forEach { key ->
                KeyCard(
                    key = key,
                    isCurrent = key.uniqueId == uiState.currentKeyFileId,
                    onCopyFingerprint = { copy(key.fingerprintHex) },
                    onCopyPublicKey = { copy(key.publicCertificateArmored) },
                    onCopyPrivateKey = { confirmPrivateKey = key },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Rotation lives with the keys, and asks first: new mail becomes unreadable to any
            // mail app until the new key is imported there.
            TextButton(
                onClick = { confirmNewKey = true },
                enabled = EmailSecretsViewModel.ROTATING !in uiState.busyIds && uiState.keys.isNotEmpty(),
            ) {
                if (EmailSecretsViewModel.ROTATING in uiState.busyIds) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(MR.string.email_secrets_new_key))
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

    // Irreversible and remote: the credential dies on the mail server, and no amount of local
    // undo brings it back.
    confirmRevoke?.let { credential ->
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text(stringResource(MR.string.email_secrets_revoke_title)) },
            text = { Text(stringResource(MR.string.email_secrets_revoke_body, credential.label)) },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmailSecretsUiAction.Revoke(credential))
                    confirmRevoke = null
                }) {
                    Text(
                        text = stringResource(MR.string.email_secrets_revoke_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = null }) {
                    Text(stringResource(MR.string.email_secrets_cancel))
                }
            },
        )
    }

    if (confirmNewKey) {
        AlertDialog(
            onDismissRequest = { confirmNewKey = false },
            title = { Text(stringResource(MR.string.email_secrets_new_key_title)) },
            text = { Text(stringResource(MR.string.email_secrets_new_key_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmailSecretsUiAction.GenerateNewKey)
                    confirmNewKey = false
                }) {
                    Text(stringResource(MR.string.email_secrets_new_key_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewKey = false }) {
                    Text(stringResource(MR.string.email_secrets_cancel))
                }
            },
        )
    }

    // Not destructive, but it puts the key that opens all your mail on the clipboard.
    confirmPrivateKey?.let { key ->
        AlertDialog(
            onDismissRequest = { confirmPrivateKey = null },
            title = { Text(stringResource(MR.string.email_secrets_private_key_title)) },
            text = { Text(stringResource(MR.string.email_secrets_private_key_body)) },
            confirmButton = {
                TextButton(onClick = {
                    copy(key.secretKeyArmored)
                    confirmPrivateKey = null
                }) {
                    Text(stringResource(MR.string.email_secrets_private_key_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPrivateKey = null }) {
                    Text(stringResource(MR.string.email_secrets_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

/** A state, not a control: filled chip, no button padding, nothing tappable about it. */
@Composable
private fun StatusChip(text: String, current: Boolean) {
    Surface(
        color = if (current) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (current) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CredentialCard(
    credential: EmailCredential,
    revealed: Boolean,
    busy: Boolean,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit,
    onRevoke: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = credential.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = credential.emailAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                // Monospace: this gets typed into a mail client by hand when it is not copied.
                text = if (revealed) credential.secret else "•".repeat(credential.secret.length.coerceAtMost(24)),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The two everyday actions, as icons with labels.
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (revealed) MR.string.email_secrets_hide else MR.string.email_secrets_reveal
                        ),
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(MR.string.email_secrets_copy_password),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Quiet, last, and far from the others: an irreversible action should not sit
                // where a harmless one is expected, and red made it the most inviting thing here.
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onRevoke) {
                        Text(
                            text = stringResource(MR.string.email_secrets_revoke),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCard(
    key: EmailKeyRef,
    isCurrent: Boolean,
    onCopyFingerprint: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onCopyPrivateKey: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            StatusChip(
                text = stringResource(
                    if (isCurrent) MR.string.email_secrets_key_current else MR.string.email_secrets_key_retired
                ),
                current = isCurrent,
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = key.displayFingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopyFingerprint) {
                    Text(stringResource(MR.string.email_secrets_copy_fingerprint))
                }
                TextButton(onClick = onCopyPublicKey) {
                    Text(stringResource(MR.string.email_secrets_copy_public_key))
                }
            }
            TextButton(onClick = onCopyPrivateKey) {
                Text(stringResource(MR.string.email_secrets_copy_private_key))
            }
        }
    }
}
