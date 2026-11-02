package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppsSettingsScreenTest {
    private val fired = mutableListOf<String>()
    private val actions = AppsSettingsActions(
        onBack = { fired += "back" },
        onMomentsSettings = { fired += "moments" },
        onVaultSettings = { fired += "vault" },
        onEmailSettings = { fired += "email" },
        onOpenWebDrop = { fired += "webdrop" },
        onLocation = { fired += "location" },
        onContactBookSettings = { fired += "contactBook" },
    )

    private fun ComposeUiTest.apps(onAction: (SettingsUiAction) -> Unit = {}) = setContent {
        MaterialTheme { AppsSettingsUi(SettingsUiState(), onAction, actions) }
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun allNineRowsAreThereInTheHubsOldOrder() = runComposeUiTest {
        apps()
        assertEquals(
            listOf(
                "nativeFeedToggle", "filesyncIconToggle", "callsBetaToggle", "momentsSettingsButton", "vaultSettingsButton",
                "emailSettingsButton", "webDropButton", "locationSettingsButton", "contactBookSettingsButton",
            ),
            AppsSettingsRowTags,
        )
        val tops = AppsSettingsRowTags.map { onNodeWithTag(it).assertExists().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun rowsStopAtTheContentCapOnAWideWindow() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(1000.dp)) { AppsSettingsUi(SettingsUiState(), {}, actions) }
            }
        }
        val bounds = onNodeWithTag("contactBookSettingsButton").getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        assertTrue(width <= 640.dp, "row is $width wide")
    }

    @Test
    fun theThreeTogglesFireTheirActions() = runComposeUiTest {
        val seen = mutableListOf<SettingsUiAction>()
        apps { seen += it }
        tap("nativeFeedToggle")
        tap("filesyncIconToggle")
        tap("callsBetaToggle")
        assertEquals(
            listOf(
                SettingsUiAction.SetUseNativeFeed(false),
                SettingsUiAction.SetFilesyncIconVisible(false),
                SettingsUiAction.SetCallsEnabled(true),
            ),
            seen,
        )
    }

    @Test
    fun theSixNavigationRowsCallTheirActions() = runComposeUiTest {
        apps()
        listOf(
            "momentsSettingsButton" to "moments",
            "vaultSettingsButton" to "vault",
            "emailSettingsButton" to "email",
            "webDropButton" to "webdrop",
            "locationSettingsButton" to "location",
            "contactBookSettingsButton" to "contactBook",
        ).forEach { (tag, route) ->
            tap(tag)
            assertEquals(route, fired.last(), tag)
        }
        assertEquals(6, fired.size)
    }

    @Test
    fun backArrowGoesBack() = runComposeUiTest {
        apps()
        onNodeWithTag("appsSettingsBackButton").performClick()
        assertEquals(listOf("back"), fired)
    }
}
