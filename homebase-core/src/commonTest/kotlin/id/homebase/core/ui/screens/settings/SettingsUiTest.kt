package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
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
                    uiState = SettingsUiState(appVersion = "1.0.0", appBuild = "12345", appBuildDate = "2023-01-01"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToConnections = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithTag("settingsTitle").assertExists()
    }

    @Test
    fun showsAllMenuItems() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0", appBuild = "12345", appBuildDate = "2023-01-01"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToConnections = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithTag("notificationsButton").assertExists()
        onNodeWithTag("securitySetupButton").assertExists()
        onNodeWithTag("appearanceButton").assertExists()
        onNodeWithTag("helpButton").assertExists()
        onNodeWithTag("deleteAccountButton").assertExists()
        onNodeWithTag("logoutButton").assertExists()
    }

    @Test
    fun showsAppVersionInfo() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "2.5.3", appBuild = "12345", appBuildDate = "2023-01-01"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToConnections = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
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
                    uiState = SettingsUiState(appVersion = "1.0.0", appBuild = "12345", appBuildDate = "2023-01-01"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToConnections = {},
                    onNavigateToNotifications = { navigated = true },
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithTag("notificationsButton").performClick()
        assertTrue(navigated)
    }

    @Test
    fun appearanceClickNavigates() = runComposeUiTest {
        var navigated = false
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0", appBuild = "12345", appBuildDate = "2023-01-01"),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToConnections = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = { navigated = true },
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithTag("appearanceButton").performClick()
        assertTrue(navigated)
    }

    @Test
    fun logoutFiresAction() = runComposeUiTest {
        var loggedOut = false
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0", appBuild = "12345", appBuildDate = "2023-01-01"),
                    onAction = { action ->
                        if (action is SettingsUiAction.LogoutClicked) {
                            loggedOut = true
                        }
                    },
                    onBackClick = {},
                    onNavigateToConnections = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithTag("logoutButton").performClick()
        assertTrue(loggedOut)
    }

    @Test
    fun deleteAccountFiresAction() = runComposeUiTest {
        var deleteClicked = false
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(appVersion = "1.0.0", appBuild = "12345", appBuildDate = "2023-01-01"),
                    onAction = { action ->
                        if (action is SettingsUiAction.DeleteAccount) {
                            deleteClicked = true
                        }
                    },
                    onBackClick = {},
                    onNavigateToConnections = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                )
            }
        }
        onNodeWithTag("deleteAccountButton").performClick()
        assertTrue(deleteClicked)
    }
}
