package id.homebase.core.ui.screens.feed.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.ui.screens.settings.SettingsDetailScaffold
import id.homebase.resources.MR
import id.homebase.resources.nav_feed
import id.homebase.resources.settings_native_feed
import org.jetbrains.compose.resources.stringResource

@Composable
fun FeedSettingsScreen(
    viewModel: FeedSettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    FeedSettingsUi(uiState = uiState, onAction = viewModel::onAction, onBackClick = onBackClick)
}

@Composable
fun FeedSettingsUi(
    uiState: FeedSettingsUiState,
    onAction: (FeedSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    SettingsDetailScaffold(
        title = stringResource(MR.string.nav_feed),
        onBack = onBackClick,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        SettingsRow(
            modifier = Modifier.testTag("nativeFeedToggle"),
            icon = Icons.Outlined.DynamicFeed,
            title = stringResource(MR.string.settings_native_feed),
            action = SettingsRowAction.Toggle(
                checked = uiState.useNativeFeed,
                onCheckedChange = { onAction(FeedSettingsUiAction.SetUseNativeFeed(it)) },
            ),
        )
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
