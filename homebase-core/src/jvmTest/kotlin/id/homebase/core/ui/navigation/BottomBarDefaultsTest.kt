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
import kotlinx.coroutines.test.runCurrent
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

    private val cursorKey = SystemDriveConstants.chatDrive.alias
    private val cursor = "{}".encodeToByteArray()

    @Test
    fun migrationKeepsExistingBarAndNeverOverridesAnExplicitChoice() = runTest {
        val fresh = db()
        BottomBarDefaultsMigration.run(fresh)
        assertFalse(VaultPreferences(fresh).iconVisible.value)
        assertFalse(EmailPreferences(fresh).iconVisible.value)

        val existing = db()
        existing.keyValue.upsertValue(cursorKey, cursor)
        BottomBarDefaultsMigration.run(existing)
        assertTrue(VaultPreferences(existing).iconVisible.value)
        assertTrue(EmailPreferences(existing).iconVisible.value)

        val chosen = db()
        chosen.keyValue.upsertValue(cursorKey, cursor)
        chosen.keyValue.upsertValue(VaultPreferences.ICON_VISIBLE_KEY, byteArrayOf(0))
        BottomBarDefaultsMigration.run(chosen)
        assertFalse(VaultPreferences(chosen).iconVisible.value)
        assertTrue(EmailPreferences(chosen).iconVisible.value)

        // Done is final: a later user choice survives re-runs, and a fresh install that syncs
        // chat afterwards is not reclassified as existing.
        EmailPreferences(existing).setIconVisible(false)
        BottomBarDefaultsMigration.run(existing)
        assertFalse(EmailPreferences(existing).iconVisible.value)
        fresh.keyValue.upsertValue(cursorKey, cursor)
        BottomBarDefaultsMigration.run(fresh)
        assertFalse(VaultPreferences(fresh).iconVisible.value)
    }

    @Test
    fun withoutThePostAuthHookThePrefsResolveAndPersistOnTheirFirstRead() = runTest {
        // Existing install, warm relaunch where onPostAuthenticated never runs: visible on the
        // very first read (no hidden flash), then frozen as explicit values.
        val existing = db()
        existing.keyValue.upsertValue(cursorKey, cursor)
        val vault = VaultPreferences(existing, migrationScope = backgroundScope)
        val email = EmailPreferences(existing, migrationScope = backgroundScope)
        assertTrue(vault.iconVisible.value)
        assertTrue(email.iconVisible.value)
        runCurrent()
        assertTrue(BottomBarDefaultsMigration.isDone(existing.keyValue))
        assertEquals(1, existing.keyValue.selectByKey(VaultPreferences.ICON_VISIBLE_KEY)?.data_?.single()?.toInt())
        assertEquals(1, existing.keyValue.selectByKey(EmailPreferences.ICON_VISIBLE_KEY)?.data_?.single()?.toInt())

        // Fresh install: hidden on first read and stays hidden once its first chat sync lands.
        val fresh = db()
        assertFalse(VaultPreferences(fresh, migrationScope = backgroundScope).iconVisible.value)
        runCurrent()
        fresh.keyValue.upsertValue(cursorKey, cursor)
        assertFalse(VaultPreferences(fresh, migrationScope = backgroundScope).iconVisible.value)
        assertFalse(EmailPreferences(fresh, migrationScope = backgroundScope).iconVisible.value)

        // An explicit choice made before the update is read as-is and never rewritten.
        val chosen = db()
        chosen.keyValue.upsertValue(cursorKey, cursor)
        chosen.keyValue.upsertValue(EmailPreferences.ICON_VISIBLE_KEY, byteArrayOf(0))
        assertFalse(EmailPreferences(chosen, migrationScope = backgroundScope).iconVisible.value)
        assertTrue(VaultPreferences(chosen, migrationScope = backgroundScope).iconVisible.value)
        runCurrent()
        assertFalse(EmailPreferences(chosen).iconVisible.value)
    }
}
