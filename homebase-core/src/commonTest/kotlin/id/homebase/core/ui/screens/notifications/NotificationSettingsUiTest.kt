package id.homebase.core.ui.screens.notifications

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NotificationSettingsUiTest {

    @Test
    fun showsTitle() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationSettingsUi(
                    uiState = NotificationSettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                    onOpenSystemSettings = {},
                )
            }
        }
        onNodeWithText("Notifications").assertExists()
    }

    @Test
    fun showsPermissionCard_whenNotGranted() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationSettingsUi(
                    uiState = NotificationSettingsUiState(isPermissionGranted = false),
                    onAction = {},
                    onBackClick = {},
                    onOpenSystemSettings = {},
                )
            }
        }
        onNodeWithText("Push Notifications are disabled").assertExists()
        onNodeWithText("Enable Notifications").assertExists()
    }

    @Test
    fun hidesPermissionCard_whenGranted() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationSettingsUi(
                    uiState = NotificationSettingsUiState(isPermissionGranted = true),
                    onAction = {},
                    onBackClick = {},
                    onOpenSystemSettings = {},
                )
            }
        }
        onNodeWithText("Push Notifications are disabled").assertDoesNotExist()
        onNodeWithText("Enable Notifications").assertDoesNotExist()
    }

    @Test
    fun showsSoundSettings() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationSettingsUi(
                    uiState = NotificationSettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                    onOpenSystemSettings = {},
                )
            }
        }
        onNodeWithText("Sounds").assertExists()
        onNodeWithText("Message Sound").assertExists()
        onNodeWithText("Play While App is Open").assertExists()
    }

    @Test
    fun showsNotificationContentSection() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationSettingsUi(
                    uiState = NotificationSettingsUiState(
                        notificationContentLevel = NotificationContentLevel.NAME_ONLY,
                    ),
                    onAction = {},
                    onBackClick = {},
                    onOpenSystemSettings = {},
                )
            }
        }
        onNodeWithText("Notification Content").assertExists()
        onNodeWithText("Name Only").assertExists()
    }

    @Test
    fun enableNotificationsFiresAction() = runComposeUiTest {
        var requestedPermission = false
        setContent {
            MaterialTheme {
                NotificationSettingsUi(
                    uiState = NotificationSettingsUiState(isPermissionGranted = false),
                    onAction = { action ->
                        if (action is NotificationSettingsUiAction.RequestPermission) {
                            requestedPermission = true
                        }
                    },
                    onBackClick = {},
                    onOpenSystemSettings = {},
                )
            }
        }
        onNodeWithText("Enable Notifications").performClick()
        assertTrue(requestedPermission)
    }

    @Test
    fun showsReRegisterButton() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationSettingsUi(
                    uiState = NotificationSettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                    onOpenSystemSettings = {},
                )
            }
        }
        onNodeWithText("Re-register Push Notifications").assertExists()
    }

    @Test
    fun showsReRegisteringState() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationSettingsUi(
                    uiState = NotificationSettingsUiState(isReRegistering = true),
                    onAction = {},
                    onBackClick = {},
                    onOpenSystemSettings = {},
                )
            }
        }
        onNodeWithText("Re-registering...").assertExists()
    }

    @Test
    fun showsBadgeCountSection() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationSettingsUi(
                    uiState = NotificationSettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                    onOpenSystemSettings = {},
                )
            }
        }
        onNodeWithText("Badge Count").assertExists()
        onNodeWithText("Include Muted Chats").assertExists()
    }
}
