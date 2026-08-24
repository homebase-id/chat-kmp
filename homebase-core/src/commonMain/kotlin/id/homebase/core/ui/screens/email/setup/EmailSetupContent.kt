package id.homebase.core.ui.screens.email.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.email_setup_address_label
import id.homebase.resources.email_setup_records_manual
import id.homebase.resources.email_setup_retry
import id.homebase.resources.email_setup_run
import id.homebase.resources.email_setup_step_credential
import id.homebase.resources.email_setup_step_credential_detail
import id.homebase.resources.email_setup_step_key
import id.homebase.resources.email_setup_step_key_detail
import id.homebase.resources.email_setup_step_mailbox
import id.homebase.resources.email_setup_step_mailbox_detail
import org.jetbrains.compose.resources.stringResource

/**
 * The setup checklist. Which step is current comes from [resolveSetupStep] — the server's state,
 * not anything this screen remembers — so closing the app mid-way and coming back simply shows
 * the same place again.
 *
 * Only the current step is actionable. Earlier ones are ticked, later ones are visible but inert,
 * because the order is a real constraint: an app password cannot be issued before a key exists.
 */
@Composable
fun EmailSetupContent(
    currentStep: EmailSetupStep,
    uiState: EmailSetupUiState,
    onAction: (EmailSetupUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        OutlinedTextField(
            value = uiState.primaryEmailAddress,
            onValueChange = { onAction(EmailSetupUiAction.AddressChanged(it)) },
            label = { Text(stringResource(MR.string.email_setup_address_label)) },
            singleLine = true,
            // The address is fixed once the mailbox exists — it is what mail is addressed to.
            enabled = currentStep == EmailSetupStep.NeedsMailbox && uiState.runningStep == null,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        SetupStepRow(
            step = EmailSetupStep.NeedsMailbox,
            currentStep = currentStep,
            uiState = uiState,
            title = stringResource(MR.string.email_setup_step_mailbox),
            detail = stringResource(MR.string.email_setup_step_mailbox_detail),
            onRun = { onAction(EmailSetupUiAction.CreateMailboxClicked) },
        )

        SetupStepRow(
            step = EmailSetupStep.NeedsKey,
            currentStep = currentStep,
            uiState = uiState,
            title = stringResource(MR.string.email_setup_step_key),
            detail = stringResource(MR.string.email_setup_step_key_detail),
            onRun = { onAction(EmailSetupUiAction.GenerateKeyClicked()) },
        )

        SetupStepRow(
            step = EmailSetupStep.NeedsAppPassword,
            currentStep = currentStep,
            uiState = uiState,
            title = stringResource(MR.string.email_setup_step_credential),
            detail = stringResource(MR.string.email_setup_step_credential_detail),
            onRun = { onAction(EmailSetupUiAction.IssueCredentialClicked) },
        )

        if (!uiState.dnsRecordsWritten) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Text(
                    text = stringResource(MR.string.email_setup_records_manual),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

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
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(onClick = { onAction(EmailSetupUiAction.ErrorDismissed) }) {
                        Text(stringResource(MR.string.email_setup_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStepRow(
    step: EmailSetupStep,
    currentStep: EmailSetupStep,
    uiState: EmailSetupUiState,
    title: String,
    detail: String,
    onRun: () -> Unit,
) {
    val done = step.isDone(currentStep)
    val isCurrent = step == currentStep
    val running = uiState.runningStep == step

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            running -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            done -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            else -> Icon(
                imageVector = Icons.Outlined.Circle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                // A step that is neither done nor current is context, not an instruction.
                color = if (done || isCurrent) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isCurrent) {
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onRun,
                    enabled = uiState.runningStep == null && uiState.primaryEmailAddress.isNotBlank(),
                ) {
                    Text(stringResource(MR.string.email_setup_run))
                }
            }
        }
    }
}
