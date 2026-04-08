package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsUiTest {

    @Test
    fun showsTitle() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithText("Settings").assertExists()
    }

    @Test
    fun showsAllMenuItems() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithText("Notifications").assertExists()
        onNodeWithText("Appearance").assertExists()
        onNodeWithText("Help").assertExists()
        onNodeWithText("Delete my account").assertExists()
        onNodeWithText("Log out").assertExists()
    }

    @Test
    fun showsAppVersionInfo() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "2.5.3"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithText("Homebase Chat").assertExists()
        onNodeWithText("Version 2.5.3").assertExists()
    }

    @Test
    fun notificationsClickNavigates() = runComposeUiTest {
        var navigated = false
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToNotifications = { navigated = true },
                    onNavigateToAppearance = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithText("Notifications").performClick()
        assertTrue(navigated)
    }

    @Test
    fun appearanceClickNavigates() = runComposeUiTest {
        var navigated = false
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = { navigated = true },
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithText("Appearance").performClick()
        assertTrue(navigated)
    }

    @Test
    fun logoutFiresAction() = runComposeUiTest {
        var loggedOut = false
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0"),
                    onAction = { action ->
                        if (action is SettingsUiAction.LogoutClicked) {
                            loggedOut = true
                        }
                    },
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithText("Log out").performClick()
        assertTrue(loggedOut)
    }

    @Test
    fun deleteAccountFiresAction() = runComposeUiTest {
        var deleteClicked = false
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0"),
                    onAction = { action ->
                        if (action is SettingsUiAction.DeleteAccount) {
                            deleteClicked = true
                        }
                    },
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithText("Delete my account").performClick()
        assertTrue(deleteClicked)
    }
}
