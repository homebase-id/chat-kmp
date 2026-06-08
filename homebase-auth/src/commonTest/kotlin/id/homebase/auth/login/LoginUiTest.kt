package id.homebase.auth.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LoginUiTest {

    @Test
    fun formState_showsSignInButton() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = false,
                        isAuthenticated = false,
                        error = null,
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("title_text").assertExists()
        onNodeWithTag("subtitle_text").assertExists()
        onNodeWithTag("login_button").assertExists()
        onNodeWithTag("create_account_button").assertExists()
    }

    @Test
    fun loadingState_showsLoadingText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = true,
                        isAuthenticated = false,
                        error = null,
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("loading_text").assertExists()
        onNodeWithTag("authenticating_text").assertExists()
        // Form should not be visible
        onNodeWithTag("login_button").assertDoesNotExist()
    }

    @Test
    fun authenticatedState_showsSuccessMessage() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = false,
                        isAuthenticated = true,
                        error = null,
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("success_text").assertExists()
        onNodeWithTag("login_button").assertDoesNotExist()
    }

    @Test
    fun errorState_showsErrorAndTryAgainButton() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = false,
                        isAuthenticated = false,
                        error = LoginError.Message("Invalid identity"),
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("error_message").assertExists()
        onNodeWithTag("try_again_button").assertExists()
        // "Sign in" should be replaced by "Try again"
        onNodeWithTag("login_button").assertDoesNotExist()
    }

    @Test
    fun createAccountAction_fires() = runComposeUiTest {
        var actionFired = false
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = false,
                        isAuthenticated = false,
                        error = null,
                    ),
                    onAction = { action ->
                        if (action is LoginUiAction.CreateAccount) {
                            actionFired = true
                        }
                    },
                )
            }
        }
        onNodeWithTag("create_account_button").performClick()
        assertTrue(actionFired)
    }

    @Test
    fun popupBlockedState_showsContinueButtonAndFires() = runComposeUiTest {
        var continueFired = false
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "frodo.dotyou.cloud",
                        isLoading = true,
                        isAuthenticated = false,
                        error = null,
                    ),
                    onAction = {},
                    pendingAuthUrl = "https://frodo.dotyou.cloud/api/owner/v1/youauth/authorize",
                    onContinueAuth = { continueFired = true },
                )
            }
        }
        // The blocked-popup prompt takes priority over the loading state.
        onNodeWithTag("popup_blocked_text").assertExists()
        onNodeWithTag("loading_text").assertDoesNotExist()
        onNodeWithTag("continue_auth_button").performClick()
        assertTrue(continueFired)
    }

    @Test
    fun loadingWithDriveProgresses_showsDriveNames() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = true,
                        isAuthenticated = false,
                        error = null,
                        driveProgresses = persistentListOf(
                            DriveProgress(driveId = "1", name = "Chat", completed = true, total = 42, count = 42, progress = 1f),
                            DriveProgress(driveId = "2", name = "Feed", count = 17, total = 17),
                        ),
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithText("Chat").assertExists()
        onNodeWithText("Feed").assertExists()
        // When driveProgresses is non-empty, "Authenticating..." should not appear
        onNodeWithText("Authenticating\u2026").assertDoesNotExist()
    }
}
