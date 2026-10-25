package id.homebase.core.util

actual val hostOs: HostOs = hostOsFromName(System.getProperty("os.name").orEmpty())
