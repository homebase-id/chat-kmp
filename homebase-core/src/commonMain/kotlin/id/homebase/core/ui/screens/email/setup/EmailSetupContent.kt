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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.email_setup_action_credential
import id.homebase.resources.email_setup_action_key
import id.homebase.resources.email_setup_action_mailbox
import id.homebase.resources.email_setup_address_label
import id.homebase.resources.email_setup_records_manual
import id.homebase.resources.email_setup_retry
import id.homebase.resources.email_setup_step_credential
import id.homebase.resources.email_setup_step_credential_detail
import id.homebase.resources.email_setup_step_key
import id.homebase.resources.email_setup_step_key_detail
import id.homebase.resources.email_setup_step_mailbox
import id.homebase.resources.email_setup_step_mailbox_detail
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Setup: the address, the one action that moves it forward, and a readout of where it has got to.
 *
 * Which step is current comes from [resolveSetupStep] — the server's state, not anything this
 * screen remembers — so closing the app mid-way and coming back shows the same place, and every
 * action is safe to press twice.
 *
 * The step list is a progress READOUT, not a set of choices. Hence one action button, sitting with
 * the address it acts on and named for what it does; and ticks and bullets rather than empty
 * rings, which read as radio buttons and invite a tap that does nothing.
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
        // Shown, not offered: the address is always mail@<identity>. Additional names are an
        // alias manager's job — each alias has to be provisioned into the mail server and
        // published in WKD, which is a feature with its own rules.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(MR.string.email_setup_address_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiState.primaryEmailAddress,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        actionFor(currentStep)?.let { action ->
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onAction(action.uiAction) },
                enabled = uiState.runningStep == null && uiState.primaryEmailAddress.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.runningStep != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(stringResource(action.label))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        SetupStepRow(
            step = EmailSetupStep.NeedsMailbox,
            currentStep = currentStep,
            uiState = uiState,
            title = stringResource(MR.string.email_setup_step_mailbox),
            detail = stringResource(MR.string.email_setup_step_mailbox_detail),
        )

        SetupStepRow(
            step = EmailSetupStep.NeedsKey,
            currentStep = currentStep,
            uiState = uiState,
            title = stringResource(MR.string.email_setup_step_key),
            detail = stringResource(MR.string.email_setup_step_key_detail),
        )

        SetupStepRow(
            step = EmailSetupStep.NeedsAppPassword,
            currentStep = currentStep,
            uiState = uiState,
            title = stringResource(MR.string.email_setup_step_credential),
            detail = stringResource(MR.string.email_setup_step_credential_detail),
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

private class StepAction(val label: StringResource, val uiAction: EmailSetupUiAction)

/** What the button does here, named for the step. "Continue" begs the question at this point. */
private fun actionFor(step: EmailSetupStep): StepAction? = when (step) {
    EmailSetupStep.NeedsMailbox ->
        StepAction(MR.string.email_setup_action_mailbox, EmailSetupUiAction.CreateMailboxClicked)

    EmailSetupStep.NeedsKey ->
        StepAction(MR.string.email_setup_action_key, EmailSetupUiAction.GenerateKeyClicked())

    EmailSetupStep.NeedsAppPassword ->
        StepAction(MR.string.email_setup_action_credential, EmailSetupUiAction.IssueCredentialClicked)

    // Permissions and the drive are dealt with before this screen; Complete has nothing left.
    else -> null
}

@Composable
private fun SetupStepRow(
    step: EmailSetupStep,
    currentStep: EmailSetupStep,
    uiState: EmailSetupUiState,
    title: String,
    detail: String,
) {
    val done = step.isDone(currentStep)
    val isCurrent = step == currentStep
    val running = uiState.runningStep == step

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.size(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            when {
                running -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)

                done -> Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )

                // A small filled dot: a bullet, not a control.
                else -> Icon(
                    imageVector = Icons.Filled.Circle,
                    contentDescription = null,
                    tint = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(8.dp),
                )
            }
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
        }
    }
}
