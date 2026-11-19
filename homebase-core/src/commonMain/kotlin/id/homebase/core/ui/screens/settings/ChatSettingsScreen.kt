package id.homebase.core.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.settings.SendQualityRows
import id.homebase.core.ui.assets.BootstrapChatOutlined
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.chat_settings_icon_always
import id.homebase.resources.chat_settings_open
import id.homebase.resources.chat_settings_show_icon
import id.homebase.resources.nav_chats
import id.homebase.resources.settings_media_autosave_unmetered
import id.homebase.resources.settings_media_autosave_unmetered_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ChatSettingsScreen(
    viewModel: ChatSettingsViewModel,
    onBack: () -> Unit,
    onOpenChat: (() -> Unit)?,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatSettingsUi(uiState = uiState, onAction = viewModel::onAction, onBack = onBack, onOpenChat = onOpenChat)
}

@Composable
internal fun ChatSettingsUi(
    uiState: ChatSettingsUiState,
    onAction: (ChatSettingsUiAction) -> Unit,
    onBack: () -> Unit,
    onOpenChat: (() -> Unit)?,
) {
    SettingsDetailScaffold(title = stringResource(MR.string.nav_chats), onBack = onBack) {
        Spacer(modifier = Modifier.height(8.dp))
        onOpenChat?.let { open ->
            SettingsRow(
                modifier = Modifier.testTag("chatOpenButton"),
                icon = BootstrapChatOutlined,
                title = stringResource(MR.string.chat_settings_open),
                action = SettingsRowAction.Navigate(open),
            )
        }
        SettingsRow(
            modifier = Modifier.testTag("chatIconToggle"),
            icon = Icons.Outlined.Visibility,
            title = stringResource(MR.string.chat_settings_show_icon),
            supportingText = stringResource(MR.string.chat_settings_icon_always),
            action = SettingsRowAction.Toggle(checked = true, enabled = false, onCheckedChange = {}),
        )
        SendQualityRows(quality = uiState.quality, onChange = { onAction(ChatSettingsUiAction.SetQuality(it)) })

        IncomingMediaSaveRow(
            checked = uiState.autoSaveIncomingMedia,
            onCheckedChange = { onAction(ChatSettingsUiAction.SetAutoSaveIncomingMedia(it)) },
            working = true,
        )
        AnimatedVisibility(visible = uiState.autoSaveIncomingMedia) {
            SettingsRow(
                modifier = Modifier.testTag("autoSaveUnmeteredToggle"),
                icon = Icons.Outlined.Wifi,
                title = stringResource(MR.string.settings_media_autosave_unmetered),
                supportingText = stringResource(MR.string.settings_media_autosave_unmetered_description),
                action = SettingsRowAction.Toggle(
                    checked = uiState.autoSaveOnUnmeteredOnly,
                    onCheckedChange = { onAction(ChatSettingsUiAction.SetAutoSaveOnUnmeteredOnly(it)) },
                ),
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
