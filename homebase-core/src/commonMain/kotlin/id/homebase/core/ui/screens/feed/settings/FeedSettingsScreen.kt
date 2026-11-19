package id.homebase.core.ui.screens.feed.settings

import id.homebase.core.settings.SendQualityRows
import id.homebase.core.ui.screens.settings.IncomingMediaSaveNotWorkingRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.RssFeed
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.ui.screens.settings.SettingsDetailScaffold
import id.homebase.resources.MR
import id.homebase.resources.feed_settings_open
import id.homebase.resources.feed_settings_show_icon
import id.homebase.resources.nav_feed
import id.homebase.resources.settings_native_feed
import org.jetbrains.compose.resources.stringResource

@Composable
fun FeedSettingsScreen(
    viewModel: FeedSettingsViewModel,
    onBackClick: () -> Unit,
    onOpenFeed: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    FeedSettingsUi(
        uiState = uiState,
        onAction = { action ->
            if (action is FeedSettingsUiAction.OpenFeedClicked) onOpenFeed() else viewModel.onAction(action)
        },
        onBackClick = onBackClick,
    )
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
            modifier = Modifier.testTag("feedOpenButton"),
            icon = HomebaseIcons.RssFeed,
            title = stringResource(MR.string.feed_settings_open),
            action = SettingsRowAction.Navigate { onAction(FeedSettingsUiAction.OpenFeedClicked) },
        )
        SettingsRow(
            modifier = Modifier.testTag("feedIconToggle"),
            icon = Icons.Outlined.Visibility,
            title = stringResource(MR.string.feed_settings_show_icon),
            action = SettingsRowAction.Toggle(
                checked = uiState.iconVisible,
                onCheckedChange = { onAction(FeedSettingsUiAction.SetIconVisible(it)) },
            ),
        )
        SettingsRow(
            modifier = Modifier.testTag("nativeFeedToggle"),
            icon = Icons.Outlined.DynamicFeed,
            title = stringResource(MR.string.settings_native_feed),
            action = SettingsRowAction.Toggle(
                checked = uiState.useNativeFeed,
                onCheckedChange = { onAction(FeedSettingsUiAction.SetUseNativeFeed(it)) },
            ),
        )
        SendQualityRows(quality = uiState.quality, onChange = { onAction(FeedSettingsUiAction.SetQuality(it)) })
        IncomingMediaSaveNotWorkingRow(
            checked = uiState.autoSaveIncomingMedia,
            onCheckedChange = { onAction(FeedSettingsUiAction.SetAutoSaveIncomingMedia(it)) },
        )
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
