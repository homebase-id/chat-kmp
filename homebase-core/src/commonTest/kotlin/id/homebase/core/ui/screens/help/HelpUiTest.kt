package id.homebase.core.ui.screens.help

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HelpUiTest {

    private fun state(
        errorCollectionEnabled: Boolean = false,
        showDeveloperMenu: Boolean = false,
        isCheckingForUpdate: Boolean = false,
        isUpdateAvailable: Boolean = false,
        isUpdateSupported: Boolean = true,
        ffmpegVersion: String? = null,
    ) = HelpUiState(
        appVersion = "1.2.3",
        ffmpegVersion = ffmpegVersion,
        isUpdateAvailable = isUpdateAvailable,
        isUpdateSupported = isUpdateSupported,
        isCheckingForUpdate = isCheckingForUpdate,
        errorCollectionEnabled = errorCollectionEnabled,
        showDeveloperMenu = showDeveloperMenu,
    )

    private fun ComposeUiTest.help(
        uiState: HelpUiState = state(),
        onAction: (HelpUiAction) -> Unit = {},
        onBackClick: () -> Unit = {},
    ) {
        setContent {
            MaterialTheme {
                HelpUi(
                    snackbarHostState = SnackbarHostState(),
                    uiState = uiState,
                    onAction = onAction,
                    onBackClick = onBackClick,
                )
            }
        }
    }

    private fun ComposeUiTest.tapRow(tag: String) {
        onNodeWithTag("helpList").performScrollToNode(hasTestTag(tag))
        onNodeWithTag(tag).performClick()
    }

    private fun leavesTheApp() = SemanticsMatcher("has an 'Open externally' click label") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == "Open externally"
    }

    private fun staysInTheApp() = SemanticsMatcher("has no click label") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == null
    }

    @Test
    fun showsTitle() = runComposeUiTest {
        help()
        onNodeWithTag("helpTitle").assertExists()
    }

    @Test
    fun backButtonNavigatesBack() = runComposeUiTest {
        var back = false
        help(onBackClick = { back = true })
        onNodeWithTag("helpBackButton").performClick()
        assertTrue(back)
    }

    /** The three rows that hand off to a browser must not wear a navigation chevron. */
    @Test
    fun browserRowsAnnounceThatTheyLeaveTheApp() = runComposeUiTest {
        help()
        listOf("supportCenterRow", "contactUsRow", "termsPrivacyRow").forEach { tag ->
            onNodeWithTag("helpList").performScrollToNode(hasTestTag(tag))
            onNodeWithTag(tag).assert(leavesTheApp())
        }
    }

    @Test
    fun supportCenterFiresAction() = runComposeUiTest {
        var fired = false
        help(onAction = { if (it is HelpUiAction.SupportCenterClicked) fired = true })
        tapRow("supportCenterRow")
        assertTrue(fired)
    }

    @Test
    fun contactUsFiresAction() = runComposeUiTest {
        var fired = false
        help(onAction = { if (it is HelpUiAction.ContactUsClicked) fired = true })
        tapRow("contactUsRow")
        assertTrue(fired)
    }

    @Test
    fun termsPrivacyFiresAction() = runComposeUiTest {
        var fired = false
        help(onAction = { if (it is HelpUiAction.TermsPrivacyClicked) fired = true })
        tapRow("termsPrivacyRow")
        assertTrue(fired)
    }

    /** Sharing the log opens a share sheet, not a browser — it must not claim otherwise. */
    @Test
    fun submitDebugLogDoesNotClaimToLeaveTheApp() = runComposeUiTest {
        help()
        onNodeWithTag("helpList").performScrollToNode(hasTestTag("submitDebugLogRow"))
        onNodeWithTag("submitDebugLogRow").assert(staysInTheApp())
    }

    @Test
    fun submitDebugLogFiresAction() = runComposeUiTest {
        var fired = false
        help(onAction = { if (it is HelpUiAction.SubmitDebugLogClicked) fired = true })
        tapRow("submitDebugLogRow")
        assertTrue(fired)
    }

    @Test
    fun errorCollectionIsTheOnlySwitchAndReportsItsState() = runComposeUiTest {
        help(uiState = state(errorCollectionEnabled = true))
        onAllNodes(isToggleable()).assertCountEquals(1)
        onNodeWithTag("errorCollectionRow").assertIsOn()
    }

    @Test
    fun errorCollectionReportsOffState() = runComposeUiTest {
        help(uiState = state(errorCollectionEnabled = false))
        onNodeWithTag("errorCollectionRow").assertIsOff()
    }

    @Test
    fun tappingTheErrorCollectionRowBodyTogglesIt() = runComposeUiTest {
        var fired = 0
        help(onAction = { if (it is HelpUiAction.ToggleErrorCollection) fired++ })
        tapRow("errorCollectionRow")
        assertEquals(1, fired)
    }

    /** The hidden gesture that unlocks the developer menu — five taps on the version row. */
    @Test
    fun fiveTapsOnTheVersionRowReachTheDeveloperGesture() = runComposeUiTest {
        var taps = 0
        help(onAction = { if (it is HelpUiAction.DeveloperClicked) taps++ })
        repeat(5) { tapRow("versionRow") }
        assertEquals(5, taps)
    }

    @Test
    fun versionRowShowsTheAppVersion() = runComposeUiTest {
        help()
        onNodeWithTag("helpList").performScrollToNode(hasTestTag("versionRow"))
        onNodeWithText("1.2.3").assertExists()
    }

    @Test
    fun developerMenuRowIsHiddenUntilUnlocked() = runComposeUiTest {
        help()
        onNodeWithTag("developerMenuRow").assertDoesNotExist()
    }

    @Test
    fun unlockedDeveloperMenuRowNavigates() = runComposeUiTest {
        var fired = false
        help(
            uiState = state(showDeveloperMenu = true),
            onAction = { if (it is HelpUiAction.DeveloperMenu) fired = true },
        )
        tapRow("developerMenuRow")
        assertTrue(fired)
    }

    @Test
    fun ffmpegRowAppearsOnlyOnceTheVersionIsKnown() = runComposeUiTest {
        help(uiState = state(ffmpegVersion = "6.0"))
        onNodeWithTag("helpList").performScrollToNode(hasTestTag("ffmpegVersionRow"))
        onNodeWithText("6.0").assertExists()
    }

    /** Nothing to press while the check is in flight, so the row carries no click action. */
    @Test
    fun theUpdateCheckInFlightIsNotTappable() = runComposeUiTest {
        help(uiState = state(isCheckingForUpdate = true))
        onNodeWithTag("helpList").performScrollToNode(hasTestTag("updateCheckingRow"))
        onNodeWithTag("updateCheckingRow").assert(
            SemanticsMatcher("has no click action") { node ->
                node.config.getOrNull(SemanticsActions.OnClick) == null
            }
        )
    }

    @Test
    fun checkForUpdateRowStatesThatYouAreUpToDate() = runComposeUiTest {
        var fired = false
        help(onAction = { if (it is HelpUiAction.CheckForUpdatedClicked) fired = true })
        onNodeWithTag("helpList").performScrollToNode(hasTestTag("checkForUpdateRow"))
        onNodeWithText("You're up to date").assertExists()
        tapRow("checkForUpdateRow")
        assertTrue(fired)
    }

    @Test
    fun anAvailableUpdateOffersTheDownload() = runComposeUiTest {
        var fired = false
        help(
            uiState = state(isUpdateAvailable = true),
            onAction = { if (it is HelpUiAction.DownloadUpdateClicked) fired = true },
        )
        tapRow("getUpdateRow")
        assertTrue(fired)
    }

    /** Downloading is in-app on Desktop, so the row must not promise a browser hand-off. */
    @Test
    fun theDownloadRowDoesNotClaimToLeaveTheApp() = runComposeUiTest {
        help(uiState = state(isUpdateAvailable = true))
        onNodeWithTag("helpList").performScrollToNode(hasTestTag("getUpdateRow"))
        onNodeWithTag("getUpdateRow").assert(staysInTheApp())
    }
}
