package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.homebase.core.settings.Language
import id.homebase.core.settings.setPlatformSystemLocale
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.language
import id.homebase.resources.language_danish
import id.homebase.resources.language_english_gb
import id.homebase.resources.language_english_us
import id.homebase.resources.language_system
import id.homebase.resources.menu_back
import id.homebase.resources.settings
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    onNavigateToNotifications: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            is SettingsUiEvent.SetLanguage -> {
                viewModel.eventConsumed()
                setPlatformSystemLocale(event.language)
            }

            SettingsUiEvent.LoggedOut -> {
                viewModel.eventConsumed()
                // navigation handled at AppNavHost / auth gate
            }

            null -> {}
        }
    }

    SettingsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        onNavigateToNotifications = onNavigateToNotifications
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsUi(
    uiState: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToNotifications: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(MR.string.settings)) }, navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = stringResource(MR.string.menu_back)
                    )
                }
            })
        }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding)
                .padding(innerPadding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Language Section
            Column(
                modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(MR.string.language),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                uiState.availableLanguages.forEach { language ->
                    LanguageOption(
                        language = language,
                        isSelected = language == uiState.selectedLanguage,
                        onClick = { onAction(SettingsUiAction.LanguageSelected(language)) })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notifications Row
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onNavigateToNotifications)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(text = "Notifications", style = MaterialTheme.typography.bodyLarge)
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    onAction(SettingsUiAction.LogoutClicked)
                }) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Log out ${uiState.loggedInDomain}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun LanguageOption(language: Language, isSelected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(getStringResourceForLanguage(language)),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun getStringResourceForLanguage(
    language: Language
): org.jetbrains.compose.resources.StringResource {
    return when (language) {
        Language.SYSTEM -> MR.string.language_system
        Language.ENGLISH_US -> MR.string.language_english_us
        Language.ENGLISH_GB -> MR.string.language_english_gb
        Language.DANISH -> MR.string.language_danish
    }
}

@Preview
@Composable
fun SettingsUiPreview() {
    HomebaseTheme {
        SettingsUi(
            uiState = SettingsUiState(loggedInDomain = "your.identity.id"),
            onAction = {},
            onBackClick = {})
    }
}
