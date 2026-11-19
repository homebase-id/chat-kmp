package id.homebase.core.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.api.image.MediaQuality
import id.homebase.api.image.SendQuality
import id.homebase.core.widget.SettingsOptionRow
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.photobase_backup_photo_quality
import id.homebase.resources.photobase_backup_video_quality
import id.homebase.resources.settings_media_quality_footer
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** [choices] as radio rows, each with [description]'s text, and [note] beneath. */
@Composable
fun MediaQualityOptions(
    selected: MediaQuality,
    description: (MediaQuality) -> StringResource,
    note: StringResource,
    onSelect: (MediaQuality) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "",
    choices: List<MediaQuality> = MediaQuality.entries,
) {
    Column(modifier = modifier.selectableGroup()) {
        choices.forEach { quality ->
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

/** A row showing the [selected] quality that opens [MediaQualityOptions] beneath it. */
@Composable
fun MediaQualityRow(
    icon: ImageVector,
    title: String,
    selected: MediaQuality,
    description: (MediaQuality) -> StringResource,
    note: StringResource,
    rowColor: Color,
    testTagPrefix: String,
    onSelect: (MediaQuality) -> Unit,
    choices: List<MediaQuality> = MediaQuality.entries,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsRow(
        modifier = Modifier.testTag(testTagPrefix + "row"),
        icon = icon,
        title = title,
        supportingText = stringResource(selected.label),
        containerColor = rowColor,
        action = SettingsRowAction.Expand(expanded = expanded, onExpandedChange = { expanded = it }),
    )
    AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        MediaQualityOptions(
            selected = selected,
            description = description,
            note = note,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(),
            testTagPrefix = testTagPrefix,
            choices = choices,
        )
    }
}

/** An app's photo quality and video quality, chosen apart. */
@Composable
fun SendQualityRows(
    quality: SendQuality,
    onChange: (SendQuality) -> Unit,
    rowColor: Color = MaterialTheme.colorScheme.surface,
    note: StringResource = MR.string.settings_media_quality_footer,
    // Only Photos keeps an Original video as recorded.
    videoChoices: List<MediaQuality> = SendQuality.VideoChoices,
) {
    MediaQualityRow(
        icon = Icons.Outlined.Image,
        title = stringResource(MR.string.photobase_backup_photo_quality),
        selected = quality.photo,
        description = { it.photoDescription },
        note = note,
        rowColor = rowColor,
        testTagPrefix = "photo-",
        onSelect = { onChange(quality.copy(photo = it)) },
    )
    MediaQualityRow(
        icon = Icons.Outlined.Movie,
        title = stringResource(MR.string.photobase_backup_video_quality),
        selected = quality.video,
        description = { it.videoDescription },
        note = note,
        rowColor = rowColor,
        testTagPrefix = "video-",
        onSelect = { onChange(quality.copy(video = it)) },
        choices = videoChoices,
    )
}
