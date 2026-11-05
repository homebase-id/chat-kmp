package id.homebase.core.ui.screens.settings

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class SettingsPaneCategoryTest {
    @Test
    fun sidebarHasAppsAndNoneOfItsPages() {
        val names = SettingsCategory.entries.map { it.name }
        assertEquals(true, "Apps" in names)
        listOf("Moments", "Vault", "Email", "Contacts").forEach { assertEquals(false, it in names, it) }
    }

    @Test
    fun sidebarRendersAnAppsEntry() = runComposeUiTest {
        setContent { MaterialTheme { SettingsSidebar(selected = SettingsCategory.General, onSelect = {}) } }
        onNodeWithText("Apps").assertExists()
    }

    @Test
    fun aRequestForVaultLandsOnVaultsPageInsideApps() = runComposeUiTest {
        SettingsPaneRequest.open(SettingsCategory.Apps, AppsPage.Vault)
        setContent {
            val requested = remember { SettingsPaneRequest.take() }
            assertEquals(SettingsCategory.Apps, requested?.category)
            AppsCategoryPage(
                appsPage = requested?.appsPage,
                root = { Text("apps root") },
                page = { Text("page ${it.name}") },
            )
        }
        onNodeWithText("page Vault").assertExists()
        onNodeWithText("apps root").assertDoesNotExist()
        assertNull(SettingsPaneRequest.take())
    }

    @Test
    fun drillingIntoVaultAndBackReturnsToTheAppsList() = runComposeUiTest {
        setContent {
            var appsPage by remember { mutableStateOf<AppsPage?>(null) }
            AppsCategoryPage(
                appsPage = appsPage,
                root = { Button(onClick = { appsPage = AppsPage.Vault }) { Text("open vault") } },
                page = { shown ->
                    Button(onClick = { appsPage = null }) { Text("back from ${shown.name}") }
                },
            )
        }
        onNodeWithText("open vault").performClick()
        onNodeWithText("back from Vault").assertExists()
        onNodeWithText("back from Vault").performClick()
        onNodeWithText("open vault").assertExists()
    }

    @Test
    fun aPlainCategoryRequestHasNoAppsPage() {
        SettingsPaneRequest.open(SettingsCategory.Account)
        assertEquals(SettingsPaneTarget(SettingsCategory.Account, null), SettingsPaneRequest.take())
    }
}
