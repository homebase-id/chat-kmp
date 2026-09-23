package id.homebase.core.ui.screens.card

import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.feed.newInMemoryJdbcDriver
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardPreferencesTest {
    private fun TestScope.databaseManager(): DatabaseManager {
        val driver = newInMemoryJdbcDriver()
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DatabaseManager({ driver }, dispatcher = dispatcher, readDispatcher = dispatcher)
    }

    @Test
    fun tapShareIsOffByDefault() = runTest {
        assertFalse(CardPreferences(databaseManager()).tapShareEnabled.value)
    }

    @Test
    fun tapSharePersistsAcrossInstances() = runTest {
        val db = databaseManager()
        CardPreferences(db).setTapShareEnabled(true)
        assertTrue(CardPreferences(db).tapShareEnabled.value)
    }

    @Test
    fun resetPicksUpAWipedStore() = runTest {
        val db = databaseManager()
        val prefs = CardPreferences(db)
        prefs.setTapShareEnabled(true)
        CardPreferences(db).setTapShareEnabled(false)
        prefs.reset()
        assertFalse(prefs.tapShareEnabled.value)
    }
}
