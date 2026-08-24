package id.homebase.core.ui.screens.email

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.ui.screens.email.components.EmailNoServerContent
import id.homebase.core.ui.screens.email.components.EmailHomeContent
import id.homebase.core.ui.screens.email.onboarding.EmailOnboardingContent
import id.homebase.core.ui.screens.email.setup.EmailSetupContent
import id.homebase.core.ui.screens.email.setup.EmailSetupStep
import id.homebase.core.ui.screens.email.setup.EmailSetupViewModel
import id.homebase.resources.MR
import id.homebase.resources.email_checking
import id.homebase.resources.email_home_subtitle
import id.homebase.resources.email_label
import id.homebase.resources.email_no_server_retry
import id.homebase.resources.email_unreachable_body
import id.homebase.resources.email_unreachable_title
import org.jetbrains.compose.resources.stringResource

/**
 * The add-on's single entry screen. Which body it shows is derived, never stored:
 *
 *  - still asking            -> spinner
 *  - the status call failed  -> retry (NOT "your server has no email" — a different answer)
 *  - server says no email    -> [EmailNoServerContent]
 *  - drive not mounted yet   -> onboarding
 *  - drive mounted           -> setup, which continues in the steps that follow
 *
 * Following Vault, onboarding lives inside this route rather than in one of its own, so the
 * screen swaps declaratively as activation resolves instead of navigating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailScreen(
    viewModel: EmailViewModel,
    setupViewModel: EmailSetupViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSecrets: () -> Unit,
    onNavigateToClientPicker: () -> Unit,
) {
    ExtendPermissionDialog(viewModel = viewModel.emailExtendPermissionViewModel)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val setupStep by viewModel.setupStep.collectAsStateWithLifecycle()
    val setupState by setupViewModel.uiState.collectAsStateWithLifecycle()


    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(MR.string.email_label)) })
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            when {
                uiState.isResolving || uiState.driveActivated == null -> EmailBusy()

                uiState.statusError != null -> EmailStatusUnavailable(
                    onRetry = { viewModel.onAction(EmailUiAction.RefreshStatusClicked) },
                )

                uiState.serverHasNoEmail -> EmailNoServerContent(
                    onRetry = { viewModel.onAction(EmailUiAction.RefreshStatusClicked) },
                    onClose = onNavigateBack,
                )

                uiState.driveActivated == false -> EmailOnboardingContent(
                    onAction = viewModel::onAction,
                )

                setupStep == EmailSetupStep.Complete -> EmailHomeContent(
                    status = uiState.serverStatus,
                    mailbox = uiState.mailboxStatus,
                    selectedClient = uiState.selectedMailClient,
                    onOpenSecrets = onNavigateToSecrets,
                    onOpenClientPicker = onNavigateToClientPicker,
                    onRefresh = { viewModel.onAction(EmailUiAction.RefreshStatusClicked) },
                    onOpenMailClient = { viewModel.onAction(EmailUiAction.OpenMailClientClicked) },
                    isRefreshing = uiState.isCheckingServer,
                )

                else -> EmailSetupContent(
                    currentStep = setupStep,
                    uiState = setupState,
                    onAction = setupViewModel::onAction,
                    onRun = {
                        setupViewModel.runSetup(
                            currentStep = { viewModel.setupStep.value },
                            refresh = { viewModel.refreshStatusNow() },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun EmailBusy() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(MR.string.email_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The call failed. Deliberately distinct from "this server has no email": telling someone their
 * server lacks a feature because a request timed out would be a lie they might act on.
 */
@Composable
private fun EmailStatusUnavailable(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(MR.string.email_unreachable_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            // Said explicitly, because the two answers look identical from here and only one of
            // them means anything about the server's capabilities.
            text = stringResource(MR.string.email_unreachable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(MR.string.email_no_server_retry))
        }
    }
}

