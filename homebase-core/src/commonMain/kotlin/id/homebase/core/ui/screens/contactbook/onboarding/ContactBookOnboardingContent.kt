package id.homebase.core.ui.screens.contactbook.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.ContactBookUiAction
import id.homebase.core.ui.screens.contactbook.ContactBookViewModel
import id.homebase.resources.MR
import id.homebase.resources.contactbook_onboarding_body_1
import id.homebase.resources.contactbook_onboarding_body_2
import id.homebase.resources.contactbook_onboarding_dismiss
import id.homebase.resources.contactbook_onboarding_setup
import id.homebase.resources.contactbook_onboarding_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactBookOnboardingContent(
    viewModel: ContactBookViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.People,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(MR.string.contactbook_onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(MR.string.contactbook_onboarding_body_1),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(MR.string.contactbook_onboarding_body_2),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(40.dp))
        FilledTonalButton(
            onClick = { viewModel.onAction(ContactBookUiAction.OnboardingGetStarted) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(MR.string.contactbook_onboarding_setup))
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = { viewModel.onAction(ContactBookUiAction.OnboardingSkip) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(MR.string.contactbook_onboarding_dismiss))
        }
    }
}
