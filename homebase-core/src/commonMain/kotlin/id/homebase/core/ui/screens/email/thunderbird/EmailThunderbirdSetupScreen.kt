package id.homebase.core.ui.screens.email.thunderbird

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.email.MailSetupPlatform
import id.homebase.core.email.Thunderbird
import id.homebase.core.email.currentMailSetupPlatform
import id.homebase.core.ui.screens.email.components.EmailKeyFileSaveEffect
import id.homebase.core.ui.screens.email.components.MailSettingsCard
import id.homebase.core.ui.screens.email.model.EmailKeyRef
import id.homebase.core.ui.screens.email.secrets.EmailSecretsUiAction
import id.homebase.core.ui.screens.email.secrets.EmailSecretsUiState
import id.homebase.core.ui.screens.email.secrets.EmailSecretsViewModel
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.email_secrets_cancel
import id.homebase.resources.email_secrets_private_key_body
import id.homebase.resources.email_secrets_private_key_confirm
import id.homebase.resources.email_secrets_private_key_title
import id.homebase.resources.email_secrets_save_private_key
import id.homebase.resources.email_secrets_save_private_key_body
import id.homebase.resources.email_secrets_save_private_key_confirm
import id.homebase.resources.email_secrets_save_private_key_title
import id.homebase.resources.email_settings_incoming
import id.homebase.resources.email_settings_outgoing
import id.homebase.resources.email_tb_copy_address
import id.homebase.resources.email_tb_copy_key
import id.homebase.resources.email_tb_copy_password
import id.homebase.resources.email_tb_get_desktop
import id.homebase.resources.email_tb_get_fdroid
import id.homebase.resources.email_tb_get_flathub
import id.homebase.resources.email_tb_get_play
import id.homebase.resources.email_tb_get_store
import id.homebase.resources.email_tb_install_android
import id.homebase.resources.email_tb_install_desktop
import id.homebase.resources.email_tb_install_title
import id.homebase.resources.email_tb_install_web
import id.homebase.resources.email_tb_intro
import id.homebase.resources.email_tb_ios_body
import id.homebase.resources.email_tb_ios_note
import id.homebase.resources.email_tb_ios_roadmap
import id.homebase.resources.email_tb_ios_title
import id.homebase.resources.email_tb_key_warning
import id.homebase.resources.email_tb_keychain_body
import id.homebase.resources.email_tb_keychain_title
import id.homebase.resources.email_tb_secrets_all
import id.homebase.resources.email_tb_step_android_1
import id.homebase.resources.email_tb_step_android_2
import id.homebase.resources.email_tb_step_android_3
import id.homebase.resources.email_tb_step_android_4
import id.homebase.resources.email_tb_step_android_5
import id.homebase.resources.email_tb_step_android_6
import id.homebase.resources.email_tb_step_desktop_1
import id.homebase.resources.email_tb_step_desktop_2
import id.homebase.resources.email_tb_step_desktop_3
import id.homebase.resources.email_tb_step_desktop_4
import id.homebase.resources.email_tb_step_desktop_5
import id.homebase.resources.email_tb_step_number
import id.homebase.resources.email_tb_steps_title
import id.homebase.resources.email_tb_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * How to read this identity's encrypted mail in Thunderbird, on the platform you are holding.
 *
 * There is no client picker: Thunderbird is the only mail app whose OpenPGP handling we have
 * verified against this server's encrypt-on-delivery, so the screen commits to it and spends its
 * space on the steps instead of on a list of half-working alternatives.
 *
 * Every value a step asks the user to type sits next to that step as a copy button, and the key
 * as a save button — the alternative is transcribing a hostname and a generated password into
 * another app by hand, where a typo produces a hang rather than an error.
 */
@Composable
fun EmailThunderbirdSetupScreen(
    viewModel: EmailSecretsViewModel,
    onBackClick: () -> Unit,
    platform: MailSetupPlatform = currentMailSetupPlatform(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    EmailKeyFileSaveEffect(viewModel = viewModel, snackbarHostState = snackbarHostState)

    EmailThunderbirdSetupUi(
        uiState = uiState,
        platform = platform,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailThunderbirdSetupUi(
    uiState: EmailSecretsUiState,
    platform: MailSetupPlatform,
    onAction: (EmailSecretsUiAction) -> Unit,
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val uriHandler = getUriHandler()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(clipEntryOf(text)) } }

    // The published key, which is the one incoming mail is encrypted to. Retired keys still open
    // older mail, but importing one of those into a fresh mail app would decrypt nothing new.
    val currentKey = uiState.keys.firstOrNull { it.uniqueId == uiState.currentKeyFileId }
        ?: uiState.keys.firstOrNull()
    val address = uiState.clientSettings?.username?.takeIf { it.isNotBlank() }
        ?: uiState.credentials.firstOrNull()?.emailAddress
    val password = uiState.credentials.firstOrNull()?.secret

    var confirmCopyKey by remember { mutableStateOf<EmailKeyRef?>(null) }
    var confirmSaveKey by remember { mutableStateOf<EmailKeyRef?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.email_tb_title),
                onBack = onBackClick,
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(MR.string.email_tb_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (platform == MailSetupPlatform.IOS) {
                SetupCard(title = stringResource(MR.string.email_tb_ios_title)) {
                    Body(stringResource(MR.string.email_tb_ios_body))
                    Body(stringResource(MR.string.email_tb_ios_note))
                    TextButton(onClick = { uriHandler.openUrl(Thunderbird.IOS_ROADMAP_URL) }) {
                        Text(stringResource(MR.string.email_tb_ios_roadmap))
                    }
                }
            } else {
                SetupCard(title = stringResource(MR.string.email_tb_install_title)) {
                    Body(
                        when (platform) {
                            MailSetupPlatform.ANDROID -> stringResource(MR.string.email_tb_install_android)
                            MailSetupPlatform.WEB -> stringResource(MR.string.email_tb_install_web)
                            else -> stringResource(MR.string.email_tb_install_desktop)
                        }
                    )
                    if (platform == MailSetupPlatform.ANDROID) {
                        Button(onClick = { uriHandler.openUrl(Thunderbird.PLAY_URL) }) {
                            Text(stringResource(MR.string.email_tb_get_play))
                        }
                        TextButton(onClick = { uriHandler.openUrl(Thunderbird.FDROID_URL) }) {
                            Text(stringResource(MR.string.email_tb_get_fdroid))
                        }
                    } else {
                        Button(onClick = { uriHandler.openUrl(Thunderbird.DESKTOP_DOWNLOAD_URL) }) {
                            Text(stringResource(MR.string.email_tb_get_desktop))
                        }
                        when (platform) {
                            MailSetupPlatform.WINDOWS -> TextButton(
                                onClick = { uriHandler.openUrl(Thunderbird.WINDOWS_STORE_URL) },
                            ) {
                                Text(stringResource(MR.string.email_tb_get_store))
                            }

                            MailSetupPlatform.LINUX -> TextButton(
                                onClick = { uriHandler.openUrl(Thunderbird.FLATHUB_URL) },
                            ) {
                                Text(stringResource(MR.string.email_tb_get_flathub))
                            }

                            else -> Unit
                        }
                    }
                }
            }

            // Android's OpenPGP lives entirely in OpenKeychain: Thunderbird there stores no keys
            // and offers no way to paste one, so setup is a two-app job whether we like it or not.
            if (platform == MailSetupPlatform.ANDROID) {
                Spacer(modifier = Modifier.height(12.dp))
                SetupCard(title = stringResource(MR.string.email_tb_keychain_title)) {
                    Body(stringResource(MR.string.email_tb_keychain_body))
                    Button(onClick = { uriHandler.openUrl(Thunderbird.OPENKEYCHAIN_PLAY_URL) }) {
                        Text(stringResource(MR.string.email_tb_get_play))
                    }
                    TextButton(onClick = { uriHandler.openUrl(Thunderbird.OPENKEYCHAIN_FDROID_URL) }) {
                        Text(stringResource(MR.string.email_tb_get_fdroid))
                    }
                }
            }

            val steps = stepsFor(platform)
            if (steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                SetupCard(title = stringResource(MR.string.email_tb_steps_title)) {
                    steps.forEachIndexed { index, step ->
                        StepRow(number = index + 1, text = stringResource(step.text)) {
                            when (step.extras) {
                                StepExtras.NONE -> Unit

                                StepExtras.KEY -> ActionRow {
                                    password?.let {
                                        StepAction(stringResource(MR.string.email_tb_copy_password)) { copy(it) }
                                    }
                                    currentKey?.let { key ->
                                        StepAction(stringResource(MR.string.email_secrets_save_private_key)) {
                                            confirmSaveKey = key
                                        }
                                        // Not on Android: OpenKeychain's clipboard import runs the
                                        // text through a PUBLIC-key-only matcher, so a pasted
                                        // secret key is rejected as unreadable
                                        // (open-keychain#2306). Offering the button there would
                                        // send people down a path that cannot work.
                                        if (platform != MailSetupPlatform.ANDROID) {
                                            StepAction(stringResource(MR.string.email_tb_copy_key)) {
                                                confirmCopyKey = key
                                            }
                                        }
                                    }
                                }

                                StepExtras.ACCOUNT -> {
                                    ActionRow {
                                        address?.let {
                                            StepAction(stringResource(MR.string.email_tb_copy_address)) { copy(it) }
                                        }
                                        password?.let {
                                            StepAction(stringResource(MR.string.email_tb_copy_password)) { copy(it) }
                                        }
                                    }
                                    uiState.clientSettings?.let { settings ->
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
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(MR.string.email_tb_key_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (uiState.clientSettings != null) {
                // iOS: no Thunderbird to walk through, but the settings are still what another
                // mail app would need, so they stay reachable rather than hidden behind a link.
                Spacer(modifier = Modifier.height(12.dp))
                SetupCard(title = stringResource(MR.string.email_tb_secrets_all)) {
                    ActionRow {
                        address?.let {
                            StepAction(stringResource(MR.string.email_tb_copy_address)) { copy(it) }
                        }
                        password?.let {
                            StepAction(stringResource(MR.string.email_tb_copy_password)) { copy(it) }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    MailSettingsCard(
                        title = stringResource(MR.string.email_settings_incoming),
                        host = uiState.clientSettings.incomingHost,
                        port = uiState.clientSettings.incomingPort,
                        security = uiState.clientSettings.incomingSocketType,
                        username = uiState.clientSettings.username,
                        onCopy = copy,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MailSettingsCard(
                        title = stringResource(MR.string.email_settings_outgoing),
                        host = uiState.clientSettings.outgoingHost,
                        port = uiState.clientSettings.outgoingPort,
                        security = uiState.clientSettings.outgoingSocketType,
                        username = uiState.clientSettings.username,
                        onCopy = copy,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Both hand over the key that opens all your mail, and both ask first — a file persists on the
    // device until someone deletes it, the clipboard until the next copy, so the wording differs.
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

    confirmCopyKey?.let { key ->
        AlertDialog(
            onDismissRequest = { confirmCopyKey = null },
            title = { Text(stringResource(MR.string.email_secrets_private_key_title)) },
            text = { Text(stringResource(MR.string.email_secrets_private_key_body)) },
            confirmButton = {
                TextButton(onClick = {
                    copy(key.secretKeyArmored)
                    confirmCopyKey = null
                }) {
                    Text(stringResource(MR.string.email_secrets_private_key_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCopyKey = null }) {
                    Text(stringResource(MR.string.email_secrets_cancel))
                }
            },
        )
    }
}

/** What a step needs at hand: nothing, the key, or the account details. */
private enum class StepExtras { NONE, KEY, ACCOUNT }

private data class ThunderbirdStep(val text: StringResource, val extras: StepExtras)

private fun stepsFor(platform: MailSetupPlatform): List<ThunderbirdStep> = when (platform) {
    MailSetupPlatform.ANDROID -> listOf(
        ThunderbirdStep(MR.string.email_tb_step_android_1, StepExtras.KEY),
        ThunderbirdStep(MR.string.email_tb_step_android_2, StepExtras.NONE),
        ThunderbirdStep(MR.string.email_tb_step_android_3, StepExtras.ACCOUNT),
        ThunderbirdStep(MR.string.email_tb_step_android_4, StepExtras.NONE),
        ThunderbirdStep(MR.string.email_tb_step_android_5, StepExtras.NONE),
        ThunderbirdStep(MR.string.email_tb_step_android_6, StepExtras.NONE),
    )

    // Nothing to walk through until there is an app to walk through.
    MailSetupPlatform.IOS -> emptyList()

    else -> listOf(
        ThunderbirdStep(MR.string.email_tb_step_desktop_1, StepExtras.KEY),
        ThunderbirdStep(MR.string.email_tb_step_desktop_2, StepExtras.ACCOUNT),
        ThunderbirdStep(MR.string.email_tb_step_desktop_3, StepExtras.NONE),
        ThunderbirdStep(MR.string.email_tb_step_desktop_4, StepExtras.NONE),
        ThunderbirdStep(MR.string.email_tb_step_desktop_5, StepExtras.NONE),
    )
}

@Composable
private fun SetupCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Body(text: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepRow(number: Int, text: String, extras: @Composable () -> Unit) {
    Row(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = stringResource(MR.string.email_tb_step_number, number),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            extras()
        }
    }
}

/**
 * FlowRow, not Row: on a narrow screen a fixed Row squeezes each button to its minimum width and
 * the labels wrap one character per line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

@Composable
private fun StepAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = label)
    }
}
