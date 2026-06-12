package id.homebase.core.location.tracking

actual fun deviceDisplayName(): String {
    // Desktop never tracks (viewer only) so this name is never written to a
    // device profile — it exists to satisfy the expect and for future use.
    val os = System.getProperty("os.name")?.takeIf { it.isNotBlank() } ?: "Desktop"
    return "$os Desktop"
}

actual fun devicePlatform(): String = "desktop"
