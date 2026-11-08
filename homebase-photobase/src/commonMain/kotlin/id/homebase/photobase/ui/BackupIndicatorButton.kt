package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.ExtendedColors
import id.homebase.core.ui.theme.LocalHomebaseExtendedColors
import id.homebase.photobase.backup.BackupIndicator
import id.homebase.photobase.backup.BackupStatus
import id.homebase.photobase.backup.backupIndicatorOf
import id.homebase.resources.MR
import id.homebase.resources.photobase_indicator_attention
import id.homebase.resources.photobase_indicator_paused
import id.homebase.resources.photobase_indicator_up_to_date
import id.homebase.resources.photobase_indicator_working
import org.jetbrains.compose.resources.stringResource

/** Green is working or done, grey will resume by itself, amber needs the user. */
@Composable
internal fun backupIndicatorColor(indicator: BackupIndicator?): Color = when (indicator) {
    BackupIndicator.Working, BackupIndicator.UpToDate -> ExtendedColors.Success
    BackupIndicator.NeedsAttention -> LocalHomebaseExtendedColors.current.warning
    BackupIndicator.Paused, null -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun backupIndicatorIcon(indicator: BackupIndicator?): ImageVector = when (indicator) {
    BackupIndicator.Working -> Icons.Outlined.CloudUpload
    BackupIndicator.UpToDate -> Icons.Outlined.CloudDone
    BackupIndicator.NeedsAttention -> Icons.Outlined.WarningAmber
    BackupIndicator.Paused, null -> Icons.Outlined.PauseCircleOutline
}

@Composable
fun BackupIndicatorButton(status: BackupStatus, backupEnabled: Boolean, onClick: () -> Unit) {
    val indicator = backupIndicatorOf(status, backupEnabled) ?: return
    val color = backupIndicatorColor(indicator)
    val icon = backupIndicatorIcon(indicator)
    IconButton(onClick = onClick, modifier = Modifier.testTag("backupIndicator")) {
        when (indicator) {
            BackupIndicator.Working -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.size(32.dp),
                    color = color,
                    strokeWidth = 2.dp,
                )
                Icon(
                    icon,
                    contentDescription = stringResource(MR.string.photobase_indicator_working),
                    modifier = Modifier.size(18.dp).testTag("backupIndicatorWorking"),
                    tint = color,
                )
            }
            BackupIndicator.UpToDate -> Icon(
                icon,
                contentDescription = stringResource(MR.string.photobase_indicator_up_to_date),
                modifier = Modifier.testTag("backupIndicatorUpToDate"),
                tint = color,
            )
            BackupIndicator.Paused -> Icon(
                icon,
                contentDescription = stringResource(MR.string.photobase_indicator_paused),
                modifier = Modifier.testTag("backupIndicatorPaused"),
                tint = color,
            )
            BackupIndicator.NeedsAttention -> Icon(
                icon,
                contentDescription = stringResource(MR.string.photobase_indicator_attention),
                modifier = Modifier.testTag("backupIndicatorAttention"),
                tint = color,
            )
        }
    }
}
