package id.homebase.core.ui.screens.email.mode

import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.mail.MailboxMode
import id.homebase.resources.MR
import id.homebase.resources.email_mode_acknowledge
import id.homebase.resources.email_mode_confirm_to_encrypted
import id.homebase.resources.email_mode_confirm_to_standard
import id.homebase.resources.email_mode_failed
import id.homebase.resources.email_mode_load_failed
import id.homebase.resources.email_mode_switch_to_encrypted_title
import id.homebase.resources.email_mode_switch_to_standard_title
import id.homebase.resources.email_mode_switching
import id.homebase.resources.email_mode_to_encrypted_apps
import id.homebase.resources.email_mode_to_encrypted_existing
import id.homebase.resources.email_mode_to_encrypted_key
import id.homebase.resources.email_mode_to_encrypted_shared
import id.homebase.resources.email_mode_to_standard_back
import id.homebase.resources.email_mode_to_standard_existing
import id.homebase.resources.email_mode_to_standard_future
import id.homebase.resources.email_mode_to_standard_key
import id.homebase.resources.email_mode_to_standard_readers
import id.homebase.resources.email_mode_type_address
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The only way to change who can read this mailbox's mail. Full screen in the error colours, with
 * the consequences spelled out and two deliberate acts before the button works: switching is rare,
 * and doing it by accident is not undoable for the mail already stored.
 */
@Composable
fun EmailModeSwitchScreen(
    viewModel: EmailModeSwitchViewModel,
    onBackClick: () -> Unit,
    onSwitched: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EmailModeSwitchUiEvent.Switched -> onSwitched()
            }
        }
    }

    EmailModeSwitchUi(uiState = uiState, onAction = viewModel::onAction, onBackClick = onBackClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailModeSwitchUi(
    uiState: EmailModeSwitchUiState,
    onAction: (EmailModeSwitchUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val target = uiState.targetMode

    Scaffold(
        containerColor = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        topBar = {
            TopAppBar(
                title = {
                    target?.let {
                        Text(
                            stringResource(
                                when (it) {
                                    MailboxMode.Standard -> MR.string.email_mode_switch_to_standard_title
                                    MailboxMode.Encrypted -> MR.string.email_mode_switch_to_encrypted_title
                                }
                            )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.errorContainer,
                    titleContentColor = colors.onErrorContainer,
                    navigationIconContentColor = colors.onErrorContainer,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            when {
                uiState.loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = colors.onErrorContainer,
                )

                target == null -> Text(
                    text = stringResource(MR.string.email_mode_load_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )

                else -> SwitchForm(target = target, uiState = uiState, onAction = onAction)
            }
        }
    }
}

@Composable
private fun SwitchForm(
    target: MailboxMode,
    uiState: EmailModeSwitchUiState,
    onAction: (EmailModeSwitchUiAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val consequences = when (target) {
        MailboxMode.Standard -> listOf(
            MR.string.email_mode_to_standard_future,
            MR.string.email_mode_to_standard_readers,
            MR.string.email_mode_to_standard_existing,
            MR.string.email_mode_to_standard_key,
            MR.string.email_mode_to_standard_back,
        )

        MailboxMode.Encrypted -> listOf(
            MR.string.email_mode_to_encrypted_apps,
            MR.string.email_mode_to_encrypted_shared,
            MR.string.email_mode_to_encrypted_key,
            MR.string.email_mode_to_encrypted_existing,
        )
    }
    val enabled = !uiState.busy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))

        consequences.forEach { consequence -> Consequence(consequence) }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = uiState.acknowledged,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = { onAction(EmailModeSwitchUiAction.AcknowledgedChanged(it)) },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = uiState.acknowledged,
                onCheckedChange = null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.error,
                    checkmarkColor = colors.onError,
                    uncheckedColor = colors.onErrorContainer,
                ),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(MR.string.email_mode_acknowledge),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = uiState.typedAddress,
            onValueChange = { onAction(EmailModeSwitchUiAction.TypedAddressChanged(it)) },
            label = { Text(stringResource(MR.string.email_mode_type_address, uiState.address)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.onErrorContainer,
                unfocusedTextColor = colors.onErrorContainer,
                focusedBorderColor = colors.error,
                unfocusedBorderColor = colors.onErrorContainer,
                focusedLabelColor = colors.error,
                unfocusedLabelColor = colors.onErrorContainer,
                cursorColor = colors.error,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onAction(EmailModeSwitchUiAction.ConfirmClicked) },
            enabled = enabled && canConfirmModeSwitch(uiState.acknowledged, uiState.typedAddress, uiState.address),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.error,
                contentColor = colors.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.onError,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(MR.string.email_mode_switching))
            } else {
                Text(
                    stringResource(
                        when (target) {
                            MailboxMode.Standard -> MR.string.email_mode_confirm_to_standard
                            MailboxMode.Encrypted -> MR.string.email_mode_confirm_to_encrypted
                        }
                    )
                )
            }
        }

        if (uiState.error == EmailModeSwitchError.SwitchFailed) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(MR.string.email_mode_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
            )
        }
    }
}

@Composable
private fun Consequence(text: StringResource) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Icon(
            imageVector = Icons.Filled.Circle,
            contentDescription = null,
            modifier = Modifier.padding(top = 9.dp).size(6.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}
