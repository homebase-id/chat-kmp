package id.homebase.core.util

enum class HostOs { Android, Ios, Mac, Windows, Linux, Web }

expect val hostOs: HostOs

internal fun hostOsFromName(osName: String): HostOs = when {
    osName.startsWith("Mac", ignoreCase = true) -> HostOs.Mac
    osName.startsWith("Windows", ignoreCase = true) -> HostOs.Windows
    else -> HostOs.Linux
}
