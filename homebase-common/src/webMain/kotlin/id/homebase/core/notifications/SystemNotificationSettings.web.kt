package id.homebase.core.notifications

import androidx.compose.runtime.Composable

@Composable
actual fun rememberOpenSystemNotificationSettings(): () -> Unit {
    // Web doesn't have a meaningful system notification settings
    return { /* No-op on web */ }
}
