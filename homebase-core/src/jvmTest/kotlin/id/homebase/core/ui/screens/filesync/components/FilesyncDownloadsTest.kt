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

    @Test
    fun aBrowserOnAPhoneOffersItsStoreAndOneOnAComputerItsOs() {
        val iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148"
        val ipad = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15"
        val android = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
        val windows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"
        val linux = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"

        assertEquals(FilesyncInstallHere.Phone(FilesyncPhoneApp.AppStore), filesyncInstallForBrowser(iphone, 5))
        assertEquals(FilesyncInstallHere.Phone(FilesyncPhoneApp.AppStore), filesyncInstallForBrowser(ipad, 5), "iPadOS says Mac but has touch")
        assertEquals(FilesyncInstallHere.Computer(FilesyncComputerOs.Mac, null), filesyncInstallForBrowser(ipad, 0))
        assertEquals(FilesyncInstallHere.Phone(FilesyncPhoneApp.GooglePlay), filesyncInstallForBrowser(android, 5), "Android before Linux")
        assertEquals(FilesyncInstallHere.Computer(FilesyncComputerOs.Windows, FilesyncDownload.Windows), filesyncInstallForBrowser(windows, 0))
        assertEquals(FilesyncInstallHere.Computer(FilesyncComputerOs.Linux, null), filesyncInstallForBrowser(linux, 0))
        assertEquals(FilesyncInstallHere.Computer(null, null), filesyncInstallForBrowser("curl/8", 0))
    }

    @Test
    fun theDesktopAppKnowsItsOsAndBuild() {
        assertEquals(FilesyncInstallHere.Computer(FilesyncComputerOs.Linux, FilesyncDownload.LinuxArm), computerWith(filesyncDownloadFor("Linux", "aarch64")))
        assertEquals(FilesyncInstallHere.Computer(null, null), computerWith(filesyncDownloadFor("FreeBSD", "amd64")))
    }
}
