package id.homebase.core.notifications

import androidx.compose.runtime.Composable

/**
 * Returns a lambda that opens the platform's native notification settings.
 * - Android: Opens the app notification channel settings
 * - iOS: Opens the app's notification settings in Settings.app
 * - Desktop: No-op (desktop notification settings are handled at OS level)
 */
@Composable
expect fun rememberOpenSystemNotificationSettings(): () -> Unit
