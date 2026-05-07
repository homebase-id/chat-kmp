package id.homebase.core.ui.screens.appearance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.settings.Language
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.setPlatformSystemLocale
import id.homebase.core.widget.SettingsClickableRow
import id.homebase.core.widget.SettingsRowItemData
import id.homebase.resources.MR
import id.homebase.resources.chat_color_wallpaper_title
import id.homebase.resources.language
import id.homebase.resources.language_danish
import id.homebase.resources.language_english_gb
import id.homebase.resources.language_english_us
import id.homebase.resources.language_system
import id.homebase.resources.menu_back
import id.homebase.resources.settings_appearance
import id.homebase.resources.theme
import id.homebase.resources.theme_dark
import id.homebase.resources.theme_light
import id.homebase.resources.theme_system
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppearanceSettingsScreen(
    viewModel: AppearanceSettingsViewModel,
    onBackClick: () -> Unit,
    onNavigateToChatColorWallpaper: () -> Unit = {},
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
        onNavigateToChatColorWallpaper = onNavigateToChatColorWallpaper,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsUi(
    uiState: AppearanceSettingsUiState,
    onAction: (AppearanceSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToChatColorWallpaper: () -> Unit = {},
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsClickableRow(
                label = stringResource(MR.string.language),
                selectedValue = SettingsRowItemData(
                    displayName = stringResource(getStringResourceForLanguage(uiState.selectedLanguage)),
                    data = uiState.selectedLanguage
                ),
                options = uiState.availableLanguages.map {
                    SettingsRowItemData(
                        displayName = stringResource(getStringResourceForLanguage(it)),
                        data = it,
                    )
                }.toPersistentList(),
                onSelected = {
                    onAction(AppearanceSettingsUiAction.LanguageSelected(it))
                },
            )

            SettingsClickableRow(
                label = stringResource(MR.string.theme),
                selectedValue = SettingsRowItemData(
                    displayName = uiState.selectedTheme.getStringResourceForTheme(),
                    data = uiState.selectedTheme
                ),
                options = ThemeState.entries.map {
                    SettingsRowItemData(
                        displayName = it.getStringResourceForTheme(),
                        data = it,
                    )
                }.toPersistentList(),
                onSelected = {
                    onAction(AppearanceSettingsUiAction.ThemeSelected(it))
                },
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToChatColorWallpaper() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(MR.string.chat_color_wallpaper_title), style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
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


