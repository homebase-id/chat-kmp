package id.homebase.core.ui.screens.webdrop.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.core.ui.screens.profile.STACK_FONT_SCALE
import id.homebase.core.ui.screens.webdrop.model.DropRow
import id.homebase.core.util.formatTimestamp
import id.homebase.core.ui.screens.webdrop.model.DropStatus
import id.homebase.resources.MR
import id.homebase.resources.webdrop_copy
import id.homebase.resources.webdrop_files_and_age
import id.homebase.resources.webdrop_files_count
import id.homebase.resources.webdrop_for_label
import id.homebase.resources.webdrop_revoke
import id.homebase.resources.webdrop_revoke_cancel
import id.homebase.resources.webdrop_revoke_confirm_message
import id.homebase.resources.webdrop_revoke_confirm_title
import id.homebase.resources.webdrop_row_clear
import id.homebase.resources.webdrop_status_expires
import id.homebase.resources.webdrop_status_opened
import id.homebase.resources.webdrop_status_removed
import id.homebase.resources.webdrop_status_waiting
import kotlin.time.Instant
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebDropRowCard(
    row: DropRow,
    onCopyLink: () -> Unit,
    onRevoke: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = row.status
    var confirmRevoke by remember { mutableStateOf(false) }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(MR.string.webdrop_revoke_confirm_title, row.receipt.name)) },
            text = { Text(stringResource(MR.string.webdrop_revoke_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRevoke = false
                        onRevoke()
                    },
                ) {
                    Text(
                        text = stringResource(MR.string.webdrop_revoke),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) {
                    Text(stringResource(MR.string.webdrop_revoke_cancel))
                }
            },
        )
    }
    var nowMs by remember { mutableLongStateOf(UnixTimeUtc.now().milliseconds) }
    if (status is DropStatus.Opened || status is DropStatus.Expiring) {
        LaunchedEffect(row.dropId) {
            while (true) {
                nowMs = UnixTimeUtc.now().milliseconds
                delay(1000)
            }
        }
    }

    val statusText = when (status) {
        DropStatus.Waiting -> stringResource(MR.string.webdrop_status_waiting)
        is DropStatus.Opened ->
            stringResource(MR.string.webdrop_status_opened, formatRemaining(status.diesAtMs - nowMs))
        is DropStatus.Expiring ->
            stringResource(MR.string.webdrop_status_expires, formatRemaining(status.diesAtMs - nowMs))
        DropStatus.Removed -> stringResource(MR.string.webdrop_status_removed)
    }
    val removed = status == DropStatus.Removed
    val colors = MaterialTheme.colorScheme
    val look = when (status) {
        DropStatus.Waiting -> StatusLook(Icons.Outlined.Link, colors.primaryContainer, colors.onPrimaryContainer, colors.primary)
        is DropStatus.Opened -> StatusLook(Icons.Outlined.LocalFireDepartment, colors.tertiaryContainer, colors.onTertiaryContainer, colors.tertiary)
        is DropStatus.Expiring -> StatusLook(Icons.Outlined.Schedule, colors.secondaryContainer, colors.onSecondaryContainer, colors.onSurfaceVariant)
        DropStatus.Removed -> StatusLook(Icons.Outlined.LinkOff, colors.surfaceContainerHighest, colors.onSurfaceVariant, colors.onSurfaceVariant)
    }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val statusColor by animateColorAsState(look.statusColor, effects)
    val badgeColor by animateColorAsState(look.container, effects)
    val badgeIconColor by animateColorAsState(look.onContainer, effects)

    // Large text needs the full width for the title, so the actions drop below it.
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    val actions: @Composable () -> Unit = {
        Row {
            if (removed) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.string.webdrop_row_clear),
                        tint = colors.onSurfaceVariant,
                    )
                }
            } else {
                IconButton(onClick = onCopyLink) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(MR.string.webdrop_copy),
                        tint = colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { confirmRevoke = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.string.webdrop_revoke),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }

    WebDropType {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = if (stacked) 16.dp else 4.dp, top = 16.dp, bottom = if (stacked) 4.dp else 16.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(badgeColor, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(look.icon, contentDescription = null, tint = badgeIconColor, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = row.receipt.name,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (removed) colors.onSurfaceVariant else colors.onSurface,
                    )
                    row.receipt.recipientName?.let { name ->
                        Text(
                            text = stringResource(MR.string.webdrop_for_label, name),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                MR.string.webdrop_files_and_age,
                                stringResource(MR.string.webdrop_files_count, row.receipt.files.size),
                                formatTimestamp(Instant.fromEpochMilliseconds(row.receipt.createdAt)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        if (row.receipt.viewOnly == true) WebDropViewOnlyBadge(muted = removed)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelLargeEmphasized.copy(fontFeatureSettings = "tnum"),
                        color = statusColor,
                    )
                }

                if (!stacked) actions()
            }
            if (stacked) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) { actions() }
            }
        }
    }
}

private class StatusLook(val icon: ImageVector, val container: Color, val onContainer: Color, val statusColor: Color)
