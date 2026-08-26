package id.homebase.core.ui.screens.email.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import id.homebase.core.util.getUriHandler
import id.homebase.core.localization.TranslationUtil
import kotlinx.io.files.Path
import id.homebase.resources.MR
import id.homebase.resources.email_secrets_key_save_failed
import id.homebase.resources.email_secrets_key_saved
import id.homebase.resources.email_secrets_save_private_key
import id.homebase.resources.email_secrets_save_private_key_body
import id.homebase.resources.email_secrets_save_private_key_confirm
import id.homebase.resources.email_secrets_save_private_key_title
import id.homebase.resources.email_settings_cert_warning
import id.homebase.resources.email_settings_incoming
import id.homebase.resources.email_settings_intro
import id.homebase.resources.email_settings_outgoing
import id.homebase.resources.email_settings_password_hint
import id.homebase.resources.email_settings_port
import id.homebase.resources.email_settings_security
import id.homebase.resources.email_settings_server
import id.homebase.resources.email_settings_title
import id.homebase.resources.email_settings_username
import id.homebase.resources.email_secrets_cancel
import id.homebase.resources.email_secrets_copy_fingerprint
import id.homebase.resources.email_secrets_copy_label
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
    val snackbarHostState = remember { SnackbarHostState() }

    // The platform save lives here, not in the ViewModel: FileSystemHandler comes from
    // getUriHandler(), a Composable accessor that is not in DI. The ViewModel writes the file
    // and hands us the path; we give it to the OS and tell the ViewModel to drop the temp.
    val fileSystemHandler = getUriHandler()
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EmailSecretsUiEvent.SaveKeyFile -> fileSystemHandler.saveFile(
                    file = Path(event.path),
                    suggestedName = event.suggestedName,
                    onSuccess = { location ->
                        viewModel.discardKeyFile(event.path)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                TranslationUtil.getString(MR.string.email_secrets_key_saved, location)
                            )
                        }
                    },
                    onError = {
                        viewModel.discardKeyFile(event.path)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                TranslationUtil.getString(MR.string.email_secrets_key_save_failed)
                            )
                        }
                    },
                )

                EmailSecretsUiEvent.KeySaveFailed -> snackbarHostState.showSnackbar(
                    TranslationUtil.getString(MR.string.email_secrets_key_save_failed)
                )
            }
        }
    }

    EmailSecretsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
    )
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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var confirmRevoke by remember { mutableStateOf<EmailCredential?>(null) }
    var confirmPrivateKey by remember { mutableStateOf<EmailKeyRef?>(null) }
    var confirmSaveKey by remember { mutableStateOf<EmailKeyRef?>(null) }
    var confirmNewKey by remember { mutableStateOf(false) }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(clipEntryOf(text)) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            // Mail-app settings first: this is what someone opening this screen while staring
            // at a half-configured mail client is actually looking for. The credentials below
            // are only useful once the server details are right.
            uiState.clientSettings?.let { settings ->
                SectionHeader(stringResource(MR.string.email_settings_title))
                Text(
                    text = stringResource(MR.string.email_settings_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                MailSettingsCard(
                    title = stringResource(MR.string.email_settings_incoming),
                    host = settings.incomingHost,
                    port = settings.incomingPort,
                    security = settings.incomingSocketType,
                    username = settings.username,
                    onCopy = copy,
                )
                Spacer(modifier = Modifier.height(8.dp))
                MailSettingsCard(
                    title = stringResource(MR.string.email_settings_outgoing),
                    host = settings.outgoingHost,
                    port = settings.outgoingPort,
                    security = settings.outgoingSocketType,
                    username = settings.username,
                    onCopy = copy,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(MR.string.email_settings_password_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Until the server has a trusted certificate, clients refuse to connect - and
                // Thunderbird fails SILENTLY on the outgoing side, losing Sent copies. Saying
                // so here costs a line and saves someone an evening.
                Text(
                    text = stringResource(MR.string.email_settings_cert_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                    onSavePrivateKey = { confirmSaveKey = key },
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
    // Saving asks separately from copying. Both hand over the private key, but a FILE persists
    // on the device until someone deletes it, so the warning names that rather than reusing the
    // clipboard wording.
    confirmSaveKey?.let { key ->
        AlertDialog(
            onDismissRequest = { confirmSaveKey = null },
            title = { Text(stringResource(MR.string.email_secrets_save_private_key_title)) },
            text = { Text(stringResource(MR.string.email_secrets_save_private_key_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmailSecretsUiAction.SavePrivateKey(key))
                    confirmSaveKey = null
                }) {
                    Text(stringResource(MR.string.email_secrets_save_private_key_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSaveKey = null }) {
                    Text(stringResource(MR.string.email_secrets_cancel))
                }
            },
        )
    }

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
    onSavePrivateKey: () -> Unit,
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

            // One row for all three. The labels drop the repeated "Copy" verb into a single
            // lead-in so they fit side by side; the private key still routes through the
            // confirmation dialog, which is where that distinction belongs — not in the layout.
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(MR.string.email_secrets_copy_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onCopyFingerprint,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(MR.string.email_secrets_copy_fingerprint))
                }
                TextButton(
                    onClick = onCopyPublicKey,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(MR.string.email_secrets_copy_public_key))
                }
                TextButton(
                    onClick = onCopyPrivateKey,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(MR.string.email_secrets_copy_private_key))
                }
                // Mail apps import a key from a FILE - clipboard is not an option in most of
                // them, and on phones it is not an option at all.
                TextButton(
                    onClick = onSavePrivateKey,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(MR.string.email_secrets_save_private_key))
                }
            }
        }
    }
}


/**
 * One server's settings, each value copyable. Copy matters more than it looks: these are typed
 * into a different application, often on a different device, and a mistyped hostname or the
 * wrong port produces a hang rather than an error message.
 */
@Composable
private fun MailSettingsCard(
    title: String,
    host: String,
    port: Int,
    security: String,
    username: String,
    onCopy: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            SettingRow(stringResource(MR.string.email_settings_server), host, onCopy)
            SettingRow(stringResource(MR.string.email_settings_port), port.toString(), onCopy)
            SettingRow(stringResource(MR.string.email_settings_security), security, null)
            SettingRow(stringResource(MR.string.email_settings_username), username, onCopy)
        }
    }
}

/** A label/value pair. [onCopy] null for values nobody types, like "SSL". */
@Composable
private fun SettingRow(label: String, value: String, onCopy: ((String) -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null) {
            IconButton(onClick = { onCopy(value) }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(MR.string.email_settings_server),
                )
            }
        }
    }
}
