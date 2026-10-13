package id.homebase.core.ui.screens.filesync.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.filesync_download_linux
import id.homebase.resources.filesync_download_linux_arm
import id.homebase.resources.filesync_download_mac_apple
import id.homebase.resources.filesync_download_mac_intel
import id.homebase.resources.filesync_download_other
import id.homebase.resources.filesync_download_windows
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The desktop installers, by their version-free names on filesync's update site. */
enum class FilesyncDownload(val asset: String, val label: StringResource) {
    Windows("homebase-filesync.exe", MR.string.filesync_download_windows),
    MacAppleChip("Homebase-Filesync-mac-aarch64.dmg", MR.string.filesync_download_mac_apple),
    MacIntel("Homebase-Filesync-mac-amd64.dmg", MR.string.filesync_download_mac_intel),
    Linux("homebase-filesync_amd64.deb", MR.string.filesync_download_linux),
    LinuxArm("homebase-filesync_arm64.deb", MR.string.filesync_download_linux_arm);

    val url: String get() = "${FilesyncProtocol.DesktopReleasesUrl}/download/$asset"
}

/**
 * The installer for the computer this app runs on, or null where it can't tell: phones, the web,
 * and an OS filesync doesn't build for.
 */
internal expect fun thisComputersFilesyncDownload(): FilesyncDownload?

/** One button per installer, this computer's first and highlighted, then a link to every other build. */
@Composable
fun FilesyncDownloadButtons(modifier: Modifier = Modifier) {
    val uriHandler = getUriHandler()
    val mine = remember { thisComputersFilesyncDownload() }
    val listed = remember(mine) {
        listOfNotNull(mine) + listOf(FilesyncDownload.Windows, FilesyncDownload.MacAppleChip, FilesyncDownload.MacIntel, FilesyncDownload.Linux).filter { it != mine }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listed.forEach { download ->
            val content: @Composable () -> Unit = {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Text(stringResource(download.label), modifier = Modifier.padding(start = 8.dp))
            }
            if (download == mine) {
                FilledTonalButton(onClick = { uriHandler.openUrl(download.url) }, modifier = Modifier.fillMaxWidth()) { content() }
            } else {
                OutlinedButton(onClick = { uriHandler.openUrl(download.url) }, modifier = Modifier.fillMaxWidth()) { content() }
            }
        }
        TextButton(onClick = { uriHandler.openUrl(FilesyncProtocol.DesktopReleasesUrl) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(MR.string.filesync_download_other))
        }
    }
}
