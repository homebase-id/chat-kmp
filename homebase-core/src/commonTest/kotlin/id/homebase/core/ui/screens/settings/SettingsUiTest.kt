package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.settings.ThemeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsUiTest {

    /** Records which nav lambda fired, so a row wired to the wrong route fails loudly. */
    private class Routes {
        val fired = mutableListOf<String>()

        fun actions() = SettingsActions(
            onBack = { fired += "back" },
            onNotifications = { fired += "notifications" },
            onAppearance = { fired += "appearance" },
            onStorage = { fired += "storage" },
            onHelp = { fired += "help" },
            onMomentsSettings = { fired += "moments" },
            onVaultSettings = { fired += "vault" },
            onLocation = { fired += "location" },
            onContactBookSettings = { fired += "contactBook" },
            onProfileEdit = { fired += "profileEdit" },
            onProfileAvatarEdit = { fired += "profileAvatarEdit" },
        )
    }

    private fun ComposeUiTest.tapRow(tag: String) {
        onNodeWithTag("settingsList").performScrollToNode(hasTestTag(tag))
        onNodeWithTag(tag).performClick()
    }

    private fun ComposeUiTest.settings(
        uiState: SettingsUiState = SettingsUiState(),
        routes: Routes = Routes(),
        onAction: (SettingsUiAction) -> Unit = {},
    ) {
        setContent {
            MaterialTheme {
                SettingsUi(uiState = uiState, onAction = onAction, actions = routes.actions())
            }
        }
    }

    @Test
    fun showsTitle() = runComposeUiTest {
        settings()
        onNodeWithTag("settingsTitle").assertExists()
    }

    @Test
    fun backButtonNavigatesBack() = runComposeUiTest {
        val routes = Routes()
        settings(routes = routes)
        onNodeWithTag("settingsBackButton").performClick()
        assertEquals(listOf("back"), routes.fired)
    }

    /**
     * Walks the hub top to bottom. Order matters: the app bar is pinned, so scrolling *up*
     * to a row can park it under the bar and swallow the tap.
     */
    @Test
    fun everyNavigationRowReachesItsOwnRoute() = runComposeUiTest {
        val routes = Routes()
        settings(routes = routes)

        val expected = listOf(
            "notificationsButton" to "notifications",
            "appearanceButton" to "appearance",
            "momentsSettingsButton" to "moments",
            "vaultSettingsButton" to "vault",
            "locationSettingsButton" to "location",
            "contactBookSettingsButton" to "contactBook",
            "storageButton" to "storage",
            "helpButton" to "help",
        )
        expected.forEach { (tag, route) ->
            tapRow(tag)
            assertEquals(route, routes.fired.lastOrNull(), "wrong route for $tag")
        }

        assertEquals(expected.map { it.second }, routes.fired)
    }

    @Test
    fun securitySetupFiresAction() = runComposeUiTest {
        var fired = false
        settings(onAction = { if (it is SettingsUiAction.SecuritySetupClicked) fired = true })
        tapRow("securitySetupButton")
        assertTrue(fired)
    }

    @Test
    fun profileHeaderOpensProfileEdit() = runComposeUiTest {
        var fired = false
        settings(onAction = { if (it is SettingsUiAction.ProfileInfoClicked) fired = true })
        tapRow("profileHeader")
        assertTrue(fired)
    }

    @Test
    fun avatarBadgeOpensAvatarEdit() = runComposeUiTest {
        var fired = false
        settings(onAction = { if (it is SettingsUiAction.AvatarClicked) fired = true })
        tapRow("avatarEditButton")
        assertTrue(fired)
    }

    @Test
    fun logoutFiresAction() = runComposeUiTest {
        var fired = false
        settings(onAction = { if (it is SettingsUiAction.LogoutClicked) fired = true })
        tapRow("logoutButton")
        assertTrue(fired)
    }

    @Test
    fun deleteAccountFiresAction() = runComposeUiTest {
        var fired = false
        settings(onAction = { if (it is SettingsUiAction.DeleteAccount) fired = true })
        tapRow("deleteAccountButton")
        assertTrue(fired)
    }

    @Test
    fun notificationRowStatesItsHealthInWords() = runComposeUiTest {
        settings(uiState = SettingsUiState(notificationStatus = NotificationVerificationStatus.OK))
        onNodeWithText("Push notifications are working").assertExists()
    }

    @Test
    fun blockedNotificationsSayWhatIsWrong() = runComposeUiTest {
        settings(
            uiState = SettingsUiState(notificationStatus = NotificationVerificationStatus.ERROR),
        )
        onNodeWithText("Push notifications aren't working").assertExists()
    }

    @Test
    fun appearanceRowShowsTheCurrentTheme() = runComposeUiTest {
        settings(uiState = SettingsUiState(theme = ThemeState.Dark))
        onNodeWithText("Theme: Dark").assertExists()
    }

    /**
     * Delete opens an in-app dialog, so it must not carry the external affordance or its
     * "Open externally" click label — Security setup one section up genuinely does leave.
     */
    @Test
    fun deleteAccountDoesNotClaimToLeaveTheApp() = runComposeUiTest {
        settings()
        onNodeWithTag("settingsList").performScrollToNode(hasTestTag("deleteAccountButton"))
        onNodeWithTag("deleteAccountButton").assert(
            SemanticsMatcher("has no click label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == null
            }
        )
    }

    @Test
    fun securitySetupStillAnnouncesThatItLeavesTheApp() = runComposeUiTest {
        settings()
        onNodeWithTag("securitySetupButton").assert(
            SemanticsMatcher("has an 'Open externally' click label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Open externally"
            }
        )
    }

    @Test
    fun storageRowDescribesItselfUntilTheSizeLands() = runComposeUiTest {
        settings()
        onNodeWithTag("settingsList").performScrollToNode(hasTestTag("storageButton"))
        onNodeWithText("Caches, database and media").assertExists()
    }

    @Test
    fun storageRowShowsTheSizeOnceMeasured() = runComposeUiTest {
        settings(uiState = SettingsUiState(storageUsedBytes = 1024L * 1024L * 3))
        onNodeWithTag("settingsList").performScrollToNode(hasTestTag("storageButton"))
        onNodeWithText("3.0 MB used on this device").assertExists()
    }
}
