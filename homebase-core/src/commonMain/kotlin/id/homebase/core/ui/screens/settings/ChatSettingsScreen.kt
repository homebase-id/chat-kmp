package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.assets.BootstrapChatOutlined
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.chat_settings_icon_always
import id.homebase.resources.chat_settings_open
import id.homebase.resources.chat_settings_show_icon
import id.homebase.resources.nav_chats
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ChatSettingsUi(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
) {
    SettingsDetailScaffold(title = stringResource(MR.string.nav_chats), onBack = onBack) {
        Spacer(modifier = Modifier.height(8.dp))
        SettingsRow(
            modifier = Modifier.testTag("chatOpenButton"),
            icon = BootstrapChatOutlined,
            title = stringResource(MR.string.chat_settings_open),
            action = SettingsRowAction.Navigate(onOpenChat),
        )
        SettingsRow(
            modifier = Modifier.testTag("chatIconToggle"),
            icon = Icons.Outlined.Visibility,
            title = stringResource(MR.string.chat_settings_show_icon),
            supportingText = stringResource(MR.string.chat_settings_icon_always),
            action = SettingsRowAction.Toggle(checked = true, onCheckedChange = {}, enabled = false),
        )
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
