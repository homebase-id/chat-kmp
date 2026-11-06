package id.homebase.core

import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.di.allModules
import org.koin.dsl.KoinAppDeclaration

/**
 * The start every platform runs, in this order: Koin, then the database, then the work that needs it
 * ([whenDatabaseOpen]). Koin comes first everywhere because iOS can be launched (a push, a location wake) before the
 * first unlock, when the database key can't be read: so nothing built at Koin start may touch the database
 * (AppStartupKoinTest). A platform adds only what is its own, such as how it waits when the database can't open yet.
 * Main thread only.
 */
object AppStartup {
    private var koinStarted = false
    private var drivers: DatabaseDriverFactory? = null
    private val database = DatabaseStartGate { DatabaseManager.initializeWithRecovery(checkNotNull(drivers)) }

    val isDatabaseOpen: Boolean get() = database.isOpen

    /** Once; [platform] adds what one platform needs, e.g. Android's context. */
    fun startKoin(platform: KoinAppDeclaration = {}) {
        if (koinStarted) return
        koinStarted = true
        org.koin.core.context.startKoin {
            platform()
            modules(allModules)
        }
    }

    /** False while the database can't be read yet: the platform calls it again when that may have changed. */
    suspend fun openDatabase(factory: DatabaseDriverFactory): Boolean {
        drivers = factory
        return database.tryOpen()
    }

    /** Runs [block] once the database is open; at once if it already is. */
    fun whenDatabaseOpen(block: () -> Unit) = database.whenOpen(block)
}
