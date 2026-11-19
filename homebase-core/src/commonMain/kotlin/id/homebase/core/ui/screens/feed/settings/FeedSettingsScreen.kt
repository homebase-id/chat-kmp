package id.homebase.core.ui.screens.feed.settings

import id.homebase.core.ui.screens.settings.NavigationBarSwitchRow
import id.homebase.core.settings.SendQualityRows
import id.homebase.core.ui.screens.settings.IncomingMediaSaveRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DynamicFeed
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
    onOpenFeed: (() -> Unit)?,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    FeedSettingsUi(uiState = uiState, onAction = viewModel::onAction, onBackClick = onBackClick, onOpenFeed = onOpenFeed)
}

@Composable
fun FeedSettingsUi(
    uiState: FeedSettingsUiState,
    onAction: (FeedSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    onOpenFeed: (() -> Unit)? = null,
) {
    SettingsDetailScaffold(
        title = stringResource(MR.string.nav_feed),
        onBack = onBackClick,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        onOpenFeed?.let { open ->
            SettingsRow(
                modifier = Modifier.testTag("feedOpenButton"),
                icon = HomebaseIcons.RssFeed,
                title = stringResource(MR.string.feed_settings_open),
                action = SettingsRowAction.Navigate(open),
            )
        }
        NavigationBarSwitchRow(
            modifier = Modifier.testTag("feedIconToggle"),
            title = stringResource(MR.string.feed_settings_show_icon),
            checked = uiState.iconVisible,
            onCheckedChange = { onAction(FeedSettingsUiAction.SetIconVisible(it)) },
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
        if (uiState.savesInBackground) {
            IncomingMediaSaveRow(
                checked = uiState.autoSaveIncomingMedia,
                onCheckedChange = { onAction(FeedSettingsUiAction.SetAutoSaveIncomingMedia(it)) },
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
