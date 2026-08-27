package id.homebase.api.device

actual fun deviceDisplayName(): String {
    val os = System.getProperty("os.name")?.takeIf { it.isNotBlank() } ?: "Desktop"
    return "$os Desktop"
}

actual fun devicePlatform(): String = "desktop"
