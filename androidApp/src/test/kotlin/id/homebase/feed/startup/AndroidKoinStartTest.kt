package id.homebase.feed.startup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.di.allModules
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// AppStartupKoinTest for the Android graph, which needs an Android context.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidKoinStartTest {

    @OptIn(DelicateCoilApi::class)
    @After
    fun noCoilSingleton() = SingletonImageLoader.reset()

    @Test
    fun nothingBuiltAtKoinStartReachesTheDatabase() {
        val noDatabaseYet = module {
            single<DatabaseManager> { error("built at Koin start, before the database opens") }
        }
        koinApplication {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(allModules + noDatabaseYet)
        }.close()
    }
}
