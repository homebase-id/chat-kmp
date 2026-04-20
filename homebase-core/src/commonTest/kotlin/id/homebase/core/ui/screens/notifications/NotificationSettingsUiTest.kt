package id.homebase.core.ui.screens.notifications

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
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
        onNodeWithTag("notificationsTitle").assertExists()
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
        onNodeWithTag("pushNotificationsDisabled").assertExists()
        onNodeWithTag("enableNotificationsButton").assertExists()
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
        onNodeWithTag("pushNotificationsDisabled").assertDoesNotExist()
        onNodeWithTag("enableNotificationsButton").assertDoesNotExist()
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
        onNodeWithTag("soundsTitle").assertExists()
        onNodeWithTag("messageSound").assertExists()
        onNodeWithTag("playWhileAppIsOpen").assertExists()
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
        onNodeWithTag("notificationContent", useUnmergedTree = true).assertExists()
        onNodeWithTag("name_only", useUnmergedTree = true).assertExists()
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
        onNodeWithTag("enableNotificationsButton").performClick()
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
        onNodeWithTag("reRegisterPushNotifications", useUnmergedTree = true).assertExists()
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
        onNodeWithTag("reRegisterPushNotifications", useUnmergedTree = true).assertExists()
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
        onNodeWithTag("badgeCount").assertExists()
        onNodeWithTag("includeMutedChats").assertExists()
    }
}
