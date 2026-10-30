package id.homebase.core.ui.screens.filesync.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import id.homebase.resources.filesync_app_store
import id.homebase.resources.filesync_google_play
import id.homebase.resources.filesync_install_linux
import id.homebase.resources.filesync_install_mac
import id.homebase.resources.filesync_install_windows
import id.homebase.resources.filesync_on_your_phone
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

/** The computers filesync builds for, by what the menu calls installing on one. */
enum class FilesyncComputerOs(val installLabel: StringResource) {
    Windows(MR.string.filesync_install_windows),
    Mac(MR.string.filesync_install_mac),
    Linux(MR.string.filesync_install_linux),
}

/** The phone app, Homebase, whose Files is Filesync's phone browser. */
enum class FilesyncPhoneApp(val url: String, val label: StringResource) {
    AppStore("https://apps.apple.com/app/id6468971238", MR.string.filesync_app_store),
    GooglePlay("https://play.google.com/store/apps/details?id=id.homebase.feed", MR.string.filesync_google_play),
}

/** What getting Filesync means where this app runs. */
sealed interface FilesyncInstallHere {
    /** The phone app itself: Filesync is already here, so nothing is offered. */
    data object InApp : FilesyncInstallHere

    /** A browser on a phone: the phone app, from that phone's store. */
    data class Phone(val app: FilesyncPhoneApp) : FilesyncInstallHere

    /** A computer: its [os] where known, and its own installer where the build can be told too. */
    data class Computer(val os: FilesyncComputerOs?, val download: FilesyncDownload?) : FilesyncInstallHere
}

internal expect fun filesyncInstallHere(): FilesyncInstallHere

/**
 * A browser's, from its user agent; iPadOS reports a Mac but has touch points. A Mac's chip and a Linux machine's
 * architecture don't show reliably, so their builds aren't picked for them.
 */
internal fun filesyncInstallForBrowser(userAgent: String, maxTouchPoints: Int): FilesyncInstallHere = when {
    "Android" in userAgent -> FilesyncInstallHere.Phone(FilesyncPhoneApp.GooglePlay)
    listOf("iPhone", "iPad", "iPod").any { it in userAgent } || ("Macintosh" in userAgent && maxTouchPoints > 1) ->
        FilesyncInstallHere.Phone(FilesyncPhoneApp.AppStore)
    "Windows" in userAgent -> FilesyncInstallHere.Computer(FilesyncComputerOs.Windows, FilesyncDownload.Windows)
    "Macintosh" in userAgent -> FilesyncInstallHere.Computer(FilesyncComputerOs.Mac, null)
    "Linux" in userAgent || "X11" in userAgent -> FilesyncInstallHere.Computer(FilesyncComputerOs.Linux, null)
    else -> FilesyncInstallHere.Computer(null, null)
}

private val FilesyncDownload.os: FilesyncComputerOs
    get() = when (this) {
        FilesyncDownload.Windows -> FilesyncComputerOs.Windows
        FilesyncDownload.MacAppleChip, FilesyncDownload.MacIntel -> FilesyncComputerOs.Mac
        FilesyncDownload.Linux, FilesyncDownload.LinuxArm -> FilesyncComputerOs.Linux
    }

internal fun computerWith(download: FilesyncDownload?) = FilesyncInstallHere.Computer(download?.os, download)

/** One button per installer, this computer's first and highlighted, then a link to every other build. */
@Composable
fun FilesyncDownloadButtons(modifier: Modifier = Modifier) {
    val uriHandler = getUriHandler()
    val mine = remember { (filesyncInstallHere() as? FilesyncInstallHere.Computer)?.download }
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

/** The phone app in both stores, for a computer's "Get Filesync". */
@Composable
fun FilesyncPhoneAppButtons(modifier: Modifier = Modifier) {
    val uriHandler = getUriHandler()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(MR.string.filesync_on_your_phone),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilesyncPhoneApp.entries.forEach { app ->
            OutlinedButton(onClick = { uriHandler.openUrl(app.url) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PhoneIphone, contentDescription = null)
                Text(stringResource(app.label), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
