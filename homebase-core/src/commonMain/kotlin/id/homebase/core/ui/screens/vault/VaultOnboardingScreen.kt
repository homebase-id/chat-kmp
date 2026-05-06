package id.homebase.core.ui.screens.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.vault_label
import id.homebase.resources.vault_onboarding_body_1
import id.homebase.resources.vault_onboarding_body_2
import id.homebase.resources.vault_onboarding_dismiss
import id.homebase.resources.vault_onboarding_setup
import id.homebase.resources.vault_onboarding_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultOnboardingScreen(
    viewModel: VaultViewModel,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.vault_label)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(MR.string.vault_onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(MR.string.vault_onboarding_body_1),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(MR.string.vault_onboarding_body_2),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(40.dp))
            FilledTonalButton(
                onClick = { viewModel.onAction(VaultUiAction.SetupClicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.string.vault_onboarding_setup))
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { viewModel.onAction(VaultUiAction.DismissOnboardingClicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.string.vault_onboarding_dismiss))
            }
        }
    }
}
