package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.calls_settings_open
import id.homebase.resources.nav_calls
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CallsSettingsUi(
    onBack: () -> Unit,
    onOpenCalls: (() -> Unit)?,
) {
    SettingsDetailScaffold(title = stringResource(MR.string.nav_calls), onBack = onBack) {
        Spacer(modifier = Modifier.height(8.dp))
        onOpenCalls?.let { open ->
            SettingsRow(
                modifier = Modifier.testTag("callsOpenButton"),
                icon = Icons.Outlined.Call,
                title = stringResource(MR.string.calls_settings_open),
                action = SettingsRowAction.Navigate(open),
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
