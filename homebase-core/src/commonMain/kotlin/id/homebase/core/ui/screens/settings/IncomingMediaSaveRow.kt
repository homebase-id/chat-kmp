package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.settings_media_autosave
import id.homebase.resources.settings_media_autosave_header
import id.homebase.resources.settings_not_working
import org.jetbrains.compose.resources.stringResource

/** The save-what-you-receive switch of an app whose save to the album isn't built yet: it's marked so. */
@Composable
internal fun IncomingMediaSaveNotWorkingRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingsSectionHeader(
        title = stringResource(MR.string.settings_media_autosave_header),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
    SettingsRow(
        modifier = Modifier.testTag("autoSaveNotWorkingToggle"),
        icon = Icons.Outlined.SaveAlt,
        title = stringResource(MR.string.settings_not_working, stringResource(MR.string.settings_media_autosave)),
        action = SettingsRowAction.Toggle(checked = checked, onCheckedChange = onCheckedChange),
    )
}
