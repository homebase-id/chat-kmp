package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.homebase.core.ui.screens.calls.settings.CallsSettingsUi
import id.homebase.core.ui.screens.calls.settings.CallsSettingsUiAction
import id.homebase.core.ui.screens.calls.settings.CallsSettingsUiState
import id.homebase.core.ui.screens.feed.settings.FeedSettingsUi
import id.homebase.core.ui.screens.feed.settings.FeedSettingsUiAction
import id.homebase.core.ui.screens.feed.settings.FeedSettingsUiState
import id.homebase.core.ui.screens.filesync.settings.FilesyncSettingsUi
import id.homebase.core.ui.screens.filesync.settings.FilesyncSettingsUiAction
import id.homebase.core.ui.screens.filesync.settings.FilesyncSettingsUiState
import kotlin.test.Test
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppsSettingsScreenTest {
    private val fired = mutableListOf<String>()
    private val actions = AppsSettingsActions(
        onBack = { fired += "back" },
        onOpenPage = { fired += it.name },
    )

    private fun ComposeUiTest.apps(screenActions: AppsSettingsActions = actions) = setContent {
        MaterialTheme { AppsSettingsScreen(screenActions) }
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun allElevenRowsAreThereInOrder() = runComposeUiTest {
        apps()
        assertEquals(
            listOf(
                "chatSettingsButton", "photobaseSettingsButton", "filesyncSettingsButton", "emailSettingsButton",
                "feedSettingsButton", "vaultSettingsButton", "locationSettingsButton", "momentsSettingsButton",
                "callsSettingsButton", "contactBookSettingsButton", "webDropButton",
            ),
            AppsSettingsRowTags,
        )
        // positionInRoot: the list now runs past the test window, where boundsInRoot clips to 0.
        val tops = AppsSettingsRowTags.map { onNodeWithTag(it).assertExists().fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun rowsStopAtTheContentCapOnAWideWindow() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(1000.dp)) { AppsSettingsScreen(actions) }
            }
        }
        val bounds = onNodeWithTag("contactBookSettingsButton").getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        assertTrue(width <= 640.dp, "row is $width wide")
    }

    @Test
    fun hasNoFeatureToggles() = runComposeUiTest {
        apps()
        listOf("nativeFeedToggle", "feedIconToggle", "filesyncIconToggle", "callsBetaToggle").forEach {
            onNodeWithTag(it).assertDoesNotExist()
        }
    }

    @Test
    fun eachAppsPageTogglesItsOwnPreference() = runComposeUiTest {
        val seen = mutableListOf<Any>()
        var page by mutableStateOf(0)
        setContent {
            MaterialTheme {
                when (page) {
                    0 -> FeedSettingsUi(FeedSettingsUiState(useNativeFeed = true), { seen += it }, {})
                    1 -> FilesyncSettingsUi(FilesyncSettingsUiState(iconVisible = true), { seen += it }, {})
                    else -> CallsSettingsUi(CallsSettingsUiState(enabled = false), { seen += it }, {})
                }
            }
        }
        tap("nativeFeedToggle")
        tap("feedIconToggle")
        page = 1
        tap("filesyncIconToggle")
        page = 2
        tap("callsBetaToggle")
        assertEquals(
            listOf(
                FeedSettingsUiAction.SetUseNativeFeed(false),
                FeedSettingsUiAction.SetIconVisible(false),
                FilesyncSettingsUiAction.SetIconVisible(false),
                CallsSettingsUiAction.SetEnabled(true),
            ),
            seen,
        )
    }

    @Test
    fun theElevenNavigationRowsOpenTheirPages() = runComposeUiTest {
        apps()
        listOf(
            "chatSettingsButton" to "Chat",
            "feedSettingsButton" to "Feed",
            "momentsSettingsButton" to "Moments",
            "vaultSettingsButton" to "Vault",
            "filesyncSettingsButton" to "Filesync",
            "photobaseSettingsButton" to "Photobase",
            "emailSettingsButton" to "Email",
            "webDropButton" to "WebDrop",
            "locationSettingsButton" to "Location",
            "contactBookSettingsButton" to "Contacts",
            "callsSettingsButton" to "Calls",
        ).forEach { (tag, route) ->
            tap(tag)
            assertEquals(route, fired.last(), tag)
        }
        assertEquals(11, fired.size)
    }

    @Test
    fun backArrowGoesBack() = runComposeUiTest {
        apps()
        onNodeWithTag("appsSettingsBackButton").performClick()
        assertEquals(listOf("back"), fired)
    }

    @Test
    fun eachPageStartsWithItsOpenRow() = runComposeUiTest {
        var page by mutableStateOf(0)
        setContent {
            MaterialTheme {
                when (page) {
                    0 -> ChatSettingsUi(ChatSettingsUiState(), {}, onBack = {}, onOpenChat = { fired += "open chat" })
                    1 -> FeedSettingsUi(FeedSettingsUiState(), { if (it == FeedSettingsUiAction.OpenFeedClicked) fired += "open feed" }, {})
                    2 -> FilesyncSettingsUi(FilesyncSettingsUiState(), { if (it == FilesyncSettingsUiAction.OpenDriveClicked) fired += "open drive" }, {})
                    3 -> WebDropSettingsUi(onBack = {}, onOpenWebDrop = { fired += "open webdrop" })
                    else -> CallsSettingsUi(CallsSettingsUiState(enabled = true), {}, {}, onOpenCalls = { fired += "open calls" })
                }
            }
        }
        listOf("chatOpenButton", "feedOpenButton", "filesyncOpenButton", "webDropOpenButton", "callsOpenButton").forEachIndexed { i, tag ->
            page = i
            waitForIdle()
            tap(tag)
        }
        assertEquals(listOf("open chat", "open feed", "open drive", "open webdrop", "open calls"), fired)
    }

    @Test
    fun chatCantLeaveTheNavigationBar() = runComposeUiTest {
        setContent { MaterialTheme { ChatSettingsUi(ChatSettingsUiState(), {}, onBack = {}, onOpenChat = {}) } }
        onNodeWithTag("chatIconToggle").assertIsOn().assertIsNotEnabled()
    }

    @Test
    fun callsOffHaveNothingToOpen() = runComposeUiTest {
        setContent { MaterialTheme { CallsSettingsUi(CallsSettingsUiState(enabled = false), {}, {}, onOpenCalls = null) } }
        onNodeWithTag("callsOpenButton").assertDoesNotExist()
    }
}
