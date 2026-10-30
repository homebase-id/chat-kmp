package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.resources.MR
import id.homebase.resources.filesync_send_chat
import id.homebase.resources.filesync_send_webdrop
import id.homebase.resources.filesync_share_other
import id.homebase.resources.filesync_share_title
import id.homebase.resources.filesync_share_title_many
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val FilesyncShareTarget.label: StringResource
    get() = when (this) {
        FilesyncShareTarget.Chat -> MR.string.filesync_send_chat
        FilesyncShareTarget.WebDrop -> MR.string.filesync_send_webdrop
        FilesyncShareTarget.OtherApp -> MR.string.filesync_share_other
    }

private val FilesyncShareTarget.icon: ImageVector
    get() = when (this) {
        FilesyncShareTarget.Chat -> Icons.AutoMirrored.Outlined.Send
        FilesyncShareTarget.WebDrop -> Icons.Outlined.Redeem
        FilesyncShareTarget.OtherApp -> Icons.Outlined.Share
    }

/** Share's one question, where to: a sheet on phones, a small dialog on wide layouts. */
@Composable
internal fun FilesyncShareSheet(nodes: List<FilesyncNode>, onAction: (FilesyncUiAction) -> Unit) {
    AdaptiveSheet(onDismiss = { onAction(FilesyncUiAction.DismissShare) }, maxWidth = 360.dp) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = if (nodes.size == 1) {
                    stringResource(MR.string.filesync_share_title, nodes.single().name)
                } else {
                    pluralStringResource(MR.plurals.filesync_share_title_many, nodes.size, nodes.size)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            FilesyncShareTarget.offered.forEach { target ->
                ListItem(
                    headlineContent = { Text(stringResource(target.label)) },
                    leadingContent = { Icon(target.icon, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    // Routed once it has animated out: the chooser's state goes with ShareTo.
                    modifier = Modifier.clickable { dismiss { onAction(FilesyncUiAction.ShareTo(target)) } },
                )
            }
        }
    }
}
