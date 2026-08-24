package id.homebase.core.ui.screens.appearance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.settings.Language
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.setPlatformSystemLocale
import id.homebase.core.widget.SettingsOptionRow
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.language
import id.homebase.resources.language_danish
import id.homebase.resources.language_english_gb
import id.homebase.resources.language_english_us
import id.homebase.resources.language_system
import id.homebase.resources.menu_back
import id.homebase.resources.settings_appearance
import id.homebase.resources.settings_haptic_feedback
import id.homebase.resources.theme
import id.homebase.resources.theme_dark
import id.homebase.resources.theme_light
import id.homebase.resources.theme_system
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppearanceSettingsScreen(
    viewModel: AppearanceSettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            is AppearanceSettingsUiEvent.SetLanguage -> {
                viewModel.eventConsumed()
                setPlatformSystemLocale(event.language)
            }

            null -> {}
        }
    }

    AppearanceSettingsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsUi(
    uiState: AppearanceSettingsUiState,
    onAction: (AppearanceSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.settings_appearance)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        var languageExpanded by rememberSaveable { mutableStateOf(false) }
        var themeExpanded by rememberSaveable { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(scrollState),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SettingsRow(
                modifier = Modifier.testTag("languageRow"),
                icon = Icons.Outlined.Language,
                title = stringResource(MR.string.language),
                supportingText = stringResource(getStringResourceForLanguage(uiState.selectedLanguage)),
                action = SettingsRowAction.Expand(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it },
                ),
            )
            AnimatedVisibility(visible = languageExpanded) {
                Column {
                    uiState.availableLanguages.forEach { language ->
                        SettingsOptionRow(
                            label = stringResource(getStringResourceForLanguage(language)),
                            selected = language == uiState.selectedLanguage,
                            onClick = {
                                languageExpanded = false
                                onAction(AppearanceSettingsUiAction.LanguageSelected(language))
                            },
                        )
                    }
                }
            }

            SettingsRow(
                modifier = Modifier.testTag("themeRow"),
                icon = Icons.Outlined.Brightness6,
                title = stringResource(MR.string.theme),
                supportingText = uiState.selectedTheme.getStringResourceForTheme(),
                action = SettingsRowAction.Expand(
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = it },
                ),
            )
            AnimatedVisibility(visible = themeExpanded) {
                Column {
                    ThemeState.entries.forEach { theme ->
                        SettingsOptionRow(
                            label = theme.getStringResourceForTheme(),
                            selected = theme == uiState.selectedTheme,
                            onClick = {
                                themeExpanded = false
                                onAction(AppearanceSettingsUiAction.ThemeSelected(theme))
                            },
                        )
                    }
                }
            }

            SettingsRow(
                modifier = Modifier.testTag("hapticFeedbackToggle"),
                icon = Icons.Outlined.Vibration,
                title = stringResource(MR.string.settings_haptic_feedback),
                action = SettingsRowAction.Toggle(
                    checked = uiState.hapticsEnabled,
                    onCheckedChange = {
                        onAction(AppearanceSettingsUiAction.HapticsEnabledChanged(it))
                    },
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun getStringResourceForLanguage(
    language: Language
): StringResource {
    return when (language) {
        Language.ENGLISH_US -> MR.string.language_english_us
        Language.ENGLISH_GB -> MR.string.language_english_gb
        Language.DANISH -> MR.string.language_danish
        else -> MR.string.language_system
    }
}

@Composable
fun ThemeState.getStringResourceForTheme(): String {
    return when (this) {
        ThemeState.System -> stringResource(MR.string.theme_system)
        ThemeState.Dark -> stringResource(MR.string.theme_dark)
        ThemeState.Light -> stringResource(MR.string.theme_light)
    }
}

fun ThemeState.getIconForTheme(): ImageVector {
    return when (this) {
        ThemeState.System -> Icons.Default.Brightness6
        ThemeState.Dark -> Icons.Default.DarkMode
        ThemeState.Light ->  Icons.Default.LightMode
    }
}


