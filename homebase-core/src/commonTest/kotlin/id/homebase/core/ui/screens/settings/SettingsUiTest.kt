package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                    uiState = SettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                    onNavigateToMomentsSettings = {}
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
                    uiState = SettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                    onNavigateToMomentsSettings = {}

                )
            }
        }
        onNodeWithTag("notificationsButton").assertExists()
        onNodeWithTag("securitySetupButton").assertExists()
        onNodeWithTag("appearanceButton").assertExists()
        onNodeWithTag("helpButton").assertExists()
        onNodeWithTag("deleteAccountButton").performScrollTo().assertExists()
        onNodeWithTag("logoutButton").performScrollTo().assertExists()
    }

    @Test
    fun notificationsClickNavigates() = runComposeUiTest {
        var navigated = false
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToNotifications = { navigated = true },
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                    onNavigateToMomentsSettings = {}

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
                    uiState = SettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = { navigated = true },
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                    onNavigateToMomentsSettings = {}

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
                    uiState = SettingsUiState(),
                    onAction = { action ->
                        if (action is SettingsUiAction.LogoutClicked) {
                            loggedOut = true
                        }
                    },
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                    onNavigateToMomentsSettings = {}

                )
            }
        }
        onNodeWithTag("logoutButton").performScrollTo().performClick()
        assertTrue(loggedOut)
    }

    @Test
    fun deleteAccountFiresAction() = runComposeUiTest {
        var deleteClicked = false
        setContent {
            MaterialTheme {
                SettingsUi(
                    uiState = SettingsUiState(),
                    onAction = { action ->
                        if (action is SettingsUiAction.DeleteAccount) {
                            deleteClicked = true
                        }
                    },
                    onBackClick = {},
                    onNavigateToNotifications = {},
                    onNavigateToAppearance = {},
                    onNavigateToStorage = {},
                    onNavigateToHelp = {},
                    onNavigateToMomentsSettings = {}
                )
            }
        }
        onNodeWithTag("deleteAccountButton").performScrollTo().performClick()
        assertTrue(deleteClicked)
    }
}
