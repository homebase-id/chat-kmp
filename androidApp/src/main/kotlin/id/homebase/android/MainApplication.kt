package id.homebase.android

import android.app.Application
import id.homebase.api.storage.SecureStorage
import id.homebase.api.storage.SharedPreferences
import id.homebase.core.di.allModules
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.context.GlobalContext.startKoin

class MainApplication : Application(), KoinComponent {
    override fun onCreate() {
        super.onCreate()

        // Initialize storage (must be done before App() which may access storage)
        SecureStorage.initialize(this)
        SharedPreferences.initialize(this) // TODO: Maybe we should use injectable UserPreferences

        runBlocking {
            // DatabaseManager.wipe { DatabaseDriverFactory(applicationContext).createDriver() } // <-- Uncomment to wipe database
            // DatabaseManager.initialize { DatabaseDriverFactory(this).createDriver() } // TODO: This is not working
        }

        startKoin {
            // Log Koin into Android logger
            androidLogger()
            // Reference Android context
            androidContext(this@MainApplication)
            // Load modules
            modules(allModules)
        }
    }
}