package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
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
    fun aRequestForVaultLandsOnVaultsPageInsideApps() {
        SettingsPaneRequest.open(SettingsCategory.Apps, AppsPage.Vault)
        assertEquals(SettingsPaneTarget(SettingsCategory.Apps, AppsPage.Vault), SettingsPaneRequest.take())
        assertNull(SettingsPaneRequest.take())
    }

    @Test
    fun aPlainCategoryRequestHasNoAppsPage() {
        SettingsPaneRequest.open(SettingsCategory.Account)
        assertEquals(SettingsPaneTarget(SettingsCategory.Account, null), SettingsPaneRequest.take())
    }
}
