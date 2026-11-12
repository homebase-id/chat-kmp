package id.homebase.core.util

enum class HostOs { Android, Ios, Mac, Windows, Linux, Web }

/**
 * The operating system the app runs on. An iPad is [HostOs.Ios]; the installed Windows MSIX is [HostOs.Windows];
 * a JVM on anything but macOS or Windows is [HostOs.Linux]; any browser is [HostOs.Web] whatever its host.
 */
expect val hostOs: HostOs

internal fun hostOsFromName(osName: String): HostOs = when {
    osName.startsWith("Mac", ignoreCase = true) -> HostOs.Mac
    osName.startsWith("Windows", ignoreCase = true) -> HostOs.Windows
    else -> HostOs.Linux
}
