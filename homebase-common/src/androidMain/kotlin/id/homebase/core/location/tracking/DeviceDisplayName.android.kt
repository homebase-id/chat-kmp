package id.homebase.core.location.tracking

import android.os.Build

actual fun deviceDisplayName(): String {
    val manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() }.orEmpty()
    val model = Build.MODEL?.takeIf { it.isNotBlank() }.orEmpty()
    val raw = when {
        model.startsWith(manufacturer, ignoreCase = true) -> model
        manufacturer.isEmpty() -> model
        else -> "$manufacturer $model"
    }.ifBlank { "Android" }
    return raw.replaceFirstChar { it.uppercaseChar() }
}

actual fun devicePlatform(): String = "android"
