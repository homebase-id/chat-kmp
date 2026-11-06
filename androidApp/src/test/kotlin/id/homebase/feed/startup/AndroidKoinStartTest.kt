package id.homebase.feed.startup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.di.allModules
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// AppStartupKoinTest for the Android graph: Android starts Koin before the database too (AppStartup).
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidKoinStartTest {

    @Test
    fun nothingBuiltAtKoinStartReachesTheDatabase() {
        val noDatabaseYet = module { single<DatabaseManager> { error("built at Koin start, before the database opens") } }
        koinApplication {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(allModules + noDatabaseYet)
        }.close()
    }
}
