package id.homebase.core.util

actual object Platform {
    actual val osName: String = System.getProperty("os.name") ?: "Desktop"
    actual val osVersion: String = System.getProperty("os.version") ?: "Unknown"
}
