package id.homebase.core.notifications

import androidx.compose.runtime.Composable

@Composable
actual fun rememberOpenSystemNotificationSettings(): () -> Unit {
    return {
        // On Desktop, try to open system notification settings.
        // On macOS this opens System Preferences > Notifications
        try {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("mac") -> {
                    Runtime.getRuntime().exec(
                        arrayOf(
                            "open", "x-apple.systempreferences:com.apple.Notifications-Settings"
                        )
                    )
                }

                os.contains("win") -> {
                    Runtime.getRuntime()
                        .exec(arrayOf("cmd", "/c", "start", "ms-settings:notifications"))
                }

                os.contains("linux") -> {
                    // Linux doesn't have a universal notification settings
                    // Try gnome-control-center as a fallback
                    Runtime.getRuntime().exec(arrayOf("gnome-control-center", "notifications"))
                }
            }
        } catch (_: Exception) {
            // Silently fail if we can't open system settings
        }
    }
}
