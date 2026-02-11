package id.homebase.core.notifications

import androidx.compose.runtime.Composable

@Composable
actual fun rememberOpenSystemNotificationSettings(): () -> Unit {
    // On iOS, notification settings are managed through iOS Settings app.
    // KMPNotifier handles the permission prompt on initialization.
    // A deeper integration would use UIApplication.openSettingsURLString.
    return { /* TODO: Open iOS Settings via platform bridge if needed */ }
}
