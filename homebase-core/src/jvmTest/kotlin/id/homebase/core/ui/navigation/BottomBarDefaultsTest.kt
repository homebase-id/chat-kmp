package id.homebase.core.ui.navigation

import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.calling.CallsPreferences
import id.homebase.core.contactbook.ContactBookPreferences
import id.homebase.core.email.EmailPreferences
import id.homebase.core.feed.newInMemoryJdbcDriver
import id.homebase.core.location.LocationPreferences
import id.homebase.core.moments.MomentsPreferences
import id.homebase.core.vault.VaultPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BottomBarDefaultsTest {
    private fun TestScope.db(): DatabaseManager {
        val driver = newInMemoryJdbcDriver()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DatabaseManager({ driver }, dispatcher = dispatcher, readDispatcher = dispatcher)
    }

    private fun routes(db: DatabaseManager) = topLevelRoutesFor(
        callsAvailable = true,
        callsIconVisible = CallsPreferences(db).iconVisible.value,
        momentsVisible = MomentsPreferences(db).iconVisible.value,
        vaultVisible = VaultPreferences(db).iconVisible.value,
        locationVisible = LocationPreferences(db).iconVisible.value,
        contactBookVisible = ContactBookPreferences(db).iconVisible.value,
        emailVisible = EmailPreferences(db).iconVisible.value,
    ).map { it.route }

    @Test
    fun freshInstallBarIsChatsCallsFeedMomentsHome() = runTest {
        val db = db()
        BottomBarDefaultsMigration.run(db)
        assertEquals(listOf(Route.ChatList, Route.Calls, Route.Feed, Route.Moments, Route.Home), routes(db))
    }

    @Test
    fun migrationKeepsExistingBarAndNeverOverridesAnExplicitChoice() = runTest {
        val fresh = db()
        assertFalse(BottomBarDefaultsMigration.run(fresh))
        assertFalse(VaultPreferences(fresh).iconVisible.value)
        assertFalse(EmailPreferences(fresh).iconVisible.value)

        val existing = db()
        existing.keyValue.upsertValue(SystemDriveConstants.chatDrive.alias, "{}".encodeToByteArray())
        assertTrue(BottomBarDefaultsMigration.run(existing))
        assertTrue(VaultPreferences(existing).iconVisible.value)
        assertTrue(EmailPreferences(existing).iconVisible.value)

        val chosen = db()
        chosen.keyValue.upsertValue(SystemDriveConstants.chatDrive.alias, "{}".encodeToByteArray())
        VaultPreferences(chosen).setIconVisible(true)
        VaultPreferences(chosen).setIconVisible(false)
        BottomBarDefaultsMigration.run(chosen)
        assertFalse(VaultPreferences(chosen).iconVisible.value)
        assertTrue(EmailPreferences(chosen).iconVisible.value)

        // Done is final: a later user choice survives re-runs, and a fresh install that syncs
        // chat afterwards is not reclassified as existing.
        EmailPreferences(existing).setIconVisible(false)
        assertFalse(BottomBarDefaultsMigration.run(existing))
        assertFalse(EmailPreferences(existing).iconVisible.value)
        fresh.keyValue.upsertValue(SystemDriveConstants.chatDrive.alias, "{}".encodeToByteArray())
        assertFalse(BottomBarDefaultsMigration.run(fresh))
        assertFalse(VaultPreferences(fresh).iconVisible.value)
    }
}
