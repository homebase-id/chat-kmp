package id.homebase.core.upgrade

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.storage.SharedPreferences
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private class TestClock(private val fixedInstant: Instant) : Clock {
    override fun now(): Instant = fixedInstant
}

private val STUB_CHECK: suspend () -> Boolean = { false }
private const val UPGRADE_URL = "https://test.homebase.id/owner/settings/version-info"

class PendingUpgradeManagerTest {

    private val testDomain = OdinId("test.homebase.id")

    private suspend fun createCredentialsManager(): CredentialsManager {
        val cm = CredentialsManager()
        val creds = ApiCredentials.create(
            domain = testDomain,
            clientAccessToken = "test-token",
            sharedSecret = SecureByteArray("test-secret".encodeToByteArray()),
        )
        cm.storeCredentials(creds)
        cm.setActiveCredentials(creds)
        return cm
    }

    @BeforeTest
    fun clearPersistedState() {
        SharedPreferences.remove(PendingUpgradeManager.KEY_FIRST_SEEN_MS)
    }

    @Test
    fun initialState_isNone() = runTest {
        val cm = createCredentialsManager()
        val manager = PendingUpgradeManager(cm, STUB_CHECK)
        assertIs<PendingUpgradeState.None>(manager.state.value)
    }

    @Test
    fun upgradeRequired_emitsSnackbar_whenFirstSeen() = runTest {
        val cm = createCredentialsManager()
        val manager = PendingUpgradeManager(cm, STUB_CHECK)
        manager.onUpgradeCheckResult(required = true)

        val state = assertIs<PendingUpgradeState.ShowSnackbar>(manager.state.value)
        assertEquals(UPGRADE_URL, state.upgradeUrl)
    }

    @Test
    fun upgradeRequired_emitsDialog_after7Days() = runTest {
        val eightDaysAgo = Clock.System.now() - 8.days
        val cm = createCredentialsManager()

        val oldManager = PendingUpgradeManager(cm, STUB_CHECK, clock = TestClock(eightDaysAgo))
        oldManager.onUpgradeCheckResult(required = true)

        val newManager = PendingUpgradeManager(cm, STUB_CHECK, clock = Clock.System)
        newManager.onUpgradeCheckResult(required = true)

        val state = assertIs<PendingUpgradeState.ShowDialog>(newManager.state.value)
        assertEquals(UPGRADE_URL, state.upgradeUrl)
    }

    @Test
    fun upgradeNotRequired_clearsState() = runTest {
        val cm = createCredentialsManager()
        val manager = PendingUpgradeManager(cm, STUB_CHECK)
        manager.onUpgradeCheckResult(required = true)
        manager.onUpgradeCheckResult(required = false)

        assertIs<PendingUpgradeState.None>(manager.state.value)
    }

    @Test
    fun dismissDialog_suppressesForSession() = runTest {
        val eightDaysAgo = Clock.System.now() - 8.days
        val cm = createCredentialsManager()

        val seedManager = PendingUpgradeManager(cm, STUB_CHECK, clock = TestClock(eightDaysAgo))
        seedManager.onUpgradeCheckResult(required = true)

        val manager = PendingUpgradeManager(cm, STUB_CHECK, clock = Clock.System)
        manager.onUpgradeCheckResult(required = true)
        assertIs<PendingUpgradeState.ShowDialog>(manager.state.value, "should be in dialog state before dismiss")

        manager.dismissDialog()
        assertIs<PendingUpgradeState.None>(manager.state.value)

        manager.onUpgradeCheckResult(required = true)
        assertIs<PendingUpgradeState.None>(manager.state.value)
    }

    @Test
    fun upgradeNotRequired_resetsSessionDismiss() = runTest {
        val eightDaysAgo = Clock.System.now() - 8.days
        val cm = createCredentialsManager()

        val seedManager = PendingUpgradeManager(cm, STUB_CHECK, clock = TestClock(eightDaysAgo))
        seedManager.onUpgradeCheckResult(required = true)

        val manager = PendingUpgradeManager(cm, STUB_CHECK, clock = Clock.System)
        manager.onUpgradeCheckResult(required = true)
        manager.dismissDialog()

        manager.onUpgradeCheckResult(required = false)
        assertIs<PendingUpgradeState.None>(manager.state.value)

        manager.onUpgradeCheckResult(required = true)
        val state = assertIs<PendingUpgradeState.ShowSnackbar>(manager.state.value)
        assertEquals(UPGRADE_URL, state.upgradeUrl)
    }

    @Test
    fun repeatedUpgradeCheck_emitsDistinctEpochs() = runTest {
        val cm = createCredentialsManager()
        val manager = PendingUpgradeManager(cm, STUB_CHECK)

        manager.onUpgradeCheckResult(required = true)
        val first = assertIs<PendingUpgradeState.ShowSnackbar>(manager.state.value)

        manager.onUpgradeCheckResult(required = true)
        val second = assertIs<PendingUpgradeState.ShowSnackbar>(manager.state.value)

        assert(first.epoch != second.epoch) { "epoch must change to break StateFlow dedup" }
    }
}
