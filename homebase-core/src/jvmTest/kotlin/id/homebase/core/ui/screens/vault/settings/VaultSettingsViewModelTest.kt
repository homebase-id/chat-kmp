package id.homebase.core.ui.screens.vault.settings

import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.feed.newInMemoryJdbcDriver
import id.homebase.core.vault.VaultPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VaultSettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.viewModel(
        available: Boolean,
        lockOn: Boolean,
    ): Pair<VaultSettingsViewModel, VaultPreferences> {
        val driver = newInMemoryJdbcDriver()
        val db = DatabaseManager(
            { driver },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            readDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val prefs = VaultPreferences(db).apply { setBiometricsEnabled(lockOn) }
        return VaultSettingsViewModel(prefs, deviceAuthAvailable = { available }) to prefs
    }

    @Test
    fun `row is shown when the device can authenticate`() = runTest(dispatcher) {
        listOf(true, false).forEach { lockOn ->
            val state = viewModel(available = true, lockOn = lockOn).first.uiState.value
            assertTrue(state.showBiometricsRow)
            assertTrue(state.deviceAuthAvailable)
        }
    }

    @Test
    fun `row is hidden when the device cannot authenticate and the lock is off`() = runTest(dispatcher) {
        val state = viewModel(available = false, lockOn = false).first.uiState.value
        assertFalse(state.showBiometricsRow)
    }

    @Test
    fun `row stays with a warning when the lock is on but the device cannot authenticate`() = runTest(dispatcher) {
        val state = viewModel(available = false, lockOn = true).first.uiState.value
        assertTrue(state.showBiometricsRow)
        assertTrue(state.biometricsEnabled)
        assertFalse(state.deviceAuthAvailable)
    }

    @Test
    fun `turning the unprotected lock off hides the row and it cannot be turned back on`() = runTest(dispatcher) {
        val (vm, prefs) = viewModel(available = false, lockOn = true)

        vm.onAction(VaultSettingsUiAction.SetBiometricsEnabled(false))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showBiometricsRow)

        vm.onAction(VaultSettingsUiAction.SetBiometricsEnabled(true))
        advanceUntilIdle()
        assertFalse(prefs.biometricsEnabled.value)
        assertFalse(vm.uiState.value.showBiometricsRow)
    }
}
