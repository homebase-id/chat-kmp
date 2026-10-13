package id.homebase.core.ui.screens.filesync.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilesyncDownloadsTest {

    @Test
    fun eachDesktopGetsItsOwnInstaller() {
        assertEquals(FilesyncDownload.Windows, filesyncDownloadFor("Windows 11", "amd64"))
        assertEquals(FilesyncDownload.Windows, filesyncDownloadFor("Windows 11", "aarch64"))
        assertEquals(FilesyncDownload.MacAppleChip, filesyncDownloadFor("Mac OS X", "aarch64"))
        assertEquals(FilesyncDownload.MacIntel, filesyncDownloadFor("Mac OS X", "x86_64"))
        assertEquals(FilesyncDownload.Linux, filesyncDownloadFor("Linux", "amd64"))
        assertEquals(FilesyncDownload.LinuxArm, filesyncDownloadFor("Linux", "aarch64"))
        assertNull(filesyncDownloadFor("FreeBSD", "amd64"))
    }

    @Test
    fun everyLinkIsOnTheUpdateSite() {
        assertEquals(
            "https://github.com/homebase-id/filesync-release-production/releases/latest/download/homebase-filesync.exe",
            FilesyncDownload.Windows.url,
        )
    }
}
