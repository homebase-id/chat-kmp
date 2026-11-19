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
import id.homebase.resources.settings_media_autosave_description
import id.homebase.resources.settings_media_autosave_header
import id.homebase.resources.settings_not_working
import org.jetbrains.compose.resources.stringResource

/** An app's save-what-you-receive switch; one whose save to the album isn't built yet says so. */
@Composable
internal fun IncomingMediaSaveRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, working: Boolean = false) {
    SettingsSectionHeader(
        title = stringResource(MR.string.settings_media_autosave_header),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
    val title = stringResource(MR.string.settings_media_autosave)
    SettingsRow(
        modifier = Modifier.testTag(if (working) "autoSaveToggle" else "autoSaveNotWorkingToggle"),
        icon = Icons.Outlined.SaveAlt,
        title = if (working) title else stringResource(MR.string.settings_not_working, title),
        supportingText = if (working) stringResource(MR.string.settings_media_autosave_description) else null,
        action = SettingsRowAction.Toggle(checked = checked, onCheckedChange = onCheckedChange),
    )
}
