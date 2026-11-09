package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import id.homebase.core.ui.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppsPageTest {
    private val rowTags = mapOf(
        AppsPage.Feed to "feedSettingsButton",
        AppsPage.Moments to "momentsSettingsButton",
        AppsPage.Vault to "vaultSettingsButton",
        AppsPage.Filesync to "filesyncSettingsButton",
        AppsPage.Email to "emailSettingsButton",
        AppsPage.Contacts to "contactBookSettingsButton",
        AppsPage.Calls to "callsSettingsButton",
        AppsPage.Photobase to "photobaseSettingsButton",
    )

    private fun actions(onBack: () -> Unit, onOpenPage: (AppsPage) -> Unit) = AppsSettingsActions(
        onBack = onBack,
        onOpenPage = onOpenPage,
        onOpenWebDrop = {},
        onLocation = {},
        photobaseAvailable = true,
    )

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    @Test
    fun everyPageHasARow() {
        assertEquals(AppsPage.entries.toSet(), rowTags.keys)
    }

    @Test
    fun everyPageRoundTripsThroughItsRoute() {
        AppsPage.entries.forEach { assertEquals(it, it.route.appsPage) }
    }

    @Test
    fun everyRowOpensItsPageInTheNavGraph() = runComposeUiTest {
        lateinit var nav: NavHostController
        setContent {
            nav = rememberNavController()
            MaterialTheme {
                NavHost(nav, startDestination = Route.AppsSettings) {
                    composable<Route.AppsSettings> {
                        AppsSettingsScreen(actions({ nav.popBackStack() }, { nav.navigate(it.route) }))
                    }
                    composable<Route.AppSettings> { entry ->
                        Text("page ${entry.toRoute<Route.AppSettings>().appsPage.name}")
                    }
                }
            }
        }
        rowTags.forEach { (page, tag) ->
            tap(tag)
            onNodeWithText("page ${page.name}").assertExists()
            runOnIdle { nav.popBackStack() }
            waitForIdle()
            onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun everyRowOpensItsPageInThePane() = runComposeUiTest {
        var appsPage by mutableStateOf<AppsPage?>(null)
        setContent {
            MaterialTheme {
                AppsCategoryPage(
                    appsPage = appsPage,
                    root = { AppsSettingsScreen(actions({}, { appsPage = it })) },
                    page = { Text("page ${it.name}") },
                )
            }
        }
        rowTags.forEach { (page, tag) ->
            tap(tag)
            onNodeWithText("page ${page.name}").assertExists()
            runOnIdle { appsPage = null }
            waitForIdle()
            onNodeWithTag(tag).assertExists()
        }
    }
}
