package id.homebase.core.ui.screens.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.plan_free_up_body
import id.homebase.resources.plan_free_up_button
import id.homebase.resources.plan_free_up_title
import id.homebase.resources.plan_more_body
import id.homebase.resources.plan_more_button
import id.homebase.resources.plan_more_title
import id.homebase.resources.plan_title
import id.homebase.resources.plan_unavailable
import id.homebase.resources.plan_your_plan
import id.homebase.resources.storage_account_full
import id.homebase.resources.storage_full_upload_failed
import org.jetbrains.compose.resources.stringResource

/**
 * Plan & storage: the identity's plan, how much of its storage is used, and what to do when it is full. The
 * storage-full banner and Settings > Storage lead here.
 */
@Composable
fun PlanSettingsScreen(viewModel: PlanSettingsViewModel, onBackClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = getUriHandler()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is PlanSettingsUiEvent.OpenUrl -> {
                viewModel.eventConsumed()
                uriHandler.openUrl(event.url)
            }
        }
    }

    PlanSettingsUi(uiState = uiState, onAction = viewModel::onAction, onBackClick = onBackClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanSettingsUi(
    uiState: PlanSettingsUiState,
    onAction: (PlanSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = { SettingsTopBar(title = stringResource(MR.string.plan_title), onBack = onBackClick) },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val usage = uiState.usage
            if (usage == null) {
                Text(
                    text = stringResource(MR.string.plan_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }

            SettingsSectionHeader(
                title = stringResource(MR.string.plan_your_plan),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            AccountStorageCard(account = usage, showPublicPage = true)
            if (uiState.isFull) {
                ErrorCard(
                    title = stringResource(MR.string.storage_full_upload_failed),
                    body = stringResource(MR.string.storage_account_full),
                )
            }

            PlanSection(
                title = stringResource(MR.string.plan_free_up_title),
                body = stringResource(MR.string.plan_free_up_body),
                button = stringResource(MR.string.plan_free_up_button),
                onClick = { onAction(PlanSettingsUiAction.SeeWhatEachAppStoresClicked) },
            )
            PlanSection(
                title = stringResource(MR.string.plan_more_title),
                body = stringResource(MR.string.plan_more_body),
                button = stringResource(MR.string.plan_more_button),
                onClick = { onAction(PlanSettingsUiAction.HowToUpgradeClicked) },
            )
        }
    }
}

@Composable
private fun PlanSection(title: String, body: String, button: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(title = title, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        OutlinedButton(onClick = onClick) { Text(button) }
    }
}
