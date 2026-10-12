package id.homebase.core.ui.screens.filesync.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.filesync_desktop_body
import id.homebase.resources.filesync_desktop_download
import id.homebase.resources.filesync_desktop_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun FilesyncDesktopAppCard(modifier: Modifier = Modifier) {
    val uriHandler = getUriHandler()
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(MR.string.filesync_desktop_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(MR.string.filesync_desktop_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { uriHandler.openUrl(FilesyncProtocol.DesktopDownloadUrl) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(MR.string.filesync_desktop_download))
            }
        }
    }
}
