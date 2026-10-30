package id.homebase.core.ui.screens.filesync.components

internal actual fun filesyncInstallHere(): FilesyncInstallHere =
    computerWith(filesyncDownloadFor(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty()))

internal fun filesyncDownloadFor(osName: String, osArch: String): FilesyncDownload? {
    val os = osName.lowercase()
    val arm = osArch.lowercase() in setOf("aarch64", "arm64")
    return when {
        os.startsWith("windows") -> FilesyncDownload.Windows
        os.startsWith("mac") -> if (arm) FilesyncDownload.MacAppleChip else FilesyncDownload.MacIntel
        os.startsWith("linux") -> if (arm) FilesyncDownload.LinuxArm else FilesyncDownload.Linux
        else -> null
    }
}
