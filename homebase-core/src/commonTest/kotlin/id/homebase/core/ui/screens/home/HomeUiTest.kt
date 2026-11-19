package id.homebase.core.ui.screens.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.test.setTestLocale
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HomeUiTest {

    @Test
    fun theEmailCardOpensEmailAndNoOtherCardIsLost() = runComposeUiTest {
        setTestLocale("en-US")
        var clicked = false
        setContent {
            MaterialTheme {
                HomeUi(
                    uiState = HomeUiState(appVersion = "1"),
                    onAction = {},
                    onNavigateToPhotobase = {},
                    onNavigateToCalls = {},
                    onNavigateToEmail = { clicked = true },
                )
            }
        }
        onNode(hasTestTag("homeEmailCard") and hasText("Email", substring = true), useUnmergedTree = false)
            .performScrollTo()
            .assertExists()
        onNodeWithTag("homeEmailCard").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(clicked)
        listOf("Vault", "WebDrop", "Drive", "Photos", "Feed", "Moments", "Location", "Contacts", "Calls").forEach {
            onNodeWithText(it).assertExists()
        }
    }

    @Test
    fun theFeedCardOpensFeedForWhenItIsOffTheNavigationBar() = runComposeUiTest {
        setTestLocale("en-US")
        var clicked = false
        setContent { MaterialTheme { HomeUi(uiState = HomeUiState(appVersion = "1"), onAction = {}, onNavigateToFeed = { clicked = true }) } }
        onNodeWithTag("homeFeedCard").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(clicked)
    }
}
