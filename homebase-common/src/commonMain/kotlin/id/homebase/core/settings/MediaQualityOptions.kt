package id.homebase.core.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.api.image.MediaQuality
import id.homebase.core.widget.SettingsOptionRow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Standard, HD and Original as radio rows, each with [description]'s text, and [note] beneath. */
@Composable
fun MediaQualityOptions(
    selected: MediaQuality,
    description: (MediaQuality) -> StringResource,
    note: StringResource,
    onSelect: (MediaQuality) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "",
) {
    Column(modifier = modifier.selectableGroup()) {
        MediaQuality.entries.forEach { quality ->
            SettingsOptionRow(
                modifier = Modifier.testTag(testTagPrefix + quality.code),
                label = stringResource(quality.label),
                supportingText = stringResource(description(quality)),
                selected = quality == selected,
                onClick = { onSelect(quality) },
            )
        }
        Text(
            text = stringResource(note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
