package id.homebase.core.di

import id.homebase.api.sync.database.DatabaseManager
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test

/**
 * AppStartup starts Koin before the database on every platform (iOS has to: it can launch before the first unlock),
 * so whatever Koin builds at start must not reach the database. This is the desktop graph; the other platforms'
 * eager definitions are few and listed in their AppModule files.
 */
class AppStartupKoinTest {

    @Test
    fun nothingBuiltAtKoinStartReachesTheDatabase() {
        val noDatabaseYet = module { single<DatabaseManager> { error("built at Koin start, before the database opens") } }
        koinApplication {
            allowOverride(true)
            modules(allModules + noDatabaseYet)
        }.close()
    }
}
