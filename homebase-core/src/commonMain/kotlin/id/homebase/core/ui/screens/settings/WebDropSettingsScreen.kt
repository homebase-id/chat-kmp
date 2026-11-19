package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.webdrop_label
import id.homebase.resources.webdrop_settings_open
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WebDropSettingsUi(
    onBack: () -> Unit,
    onOpenWebDrop: () -> Unit,
) {
    SettingsDetailScaffold(title = stringResource(MR.string.webdrop_label), onBack = onBack) {
        Spacer(modifier = Modifier.height(8.dp))
        SettingsRow(
            modifier = Modifier.testTag("webDropOpenButton"),
            icon = Icons.Outlined.Redeem,
            title = stringResource(MR.string.webdrop_settings_open),
            action = SettingsRowAction.Navigate(onOpenWebDrop),
        )
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
