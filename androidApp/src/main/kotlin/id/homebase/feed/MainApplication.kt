package id.homebase.feed

import android.app.Application
import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.notification.NotifierManager
import com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration
import id.homebase.api.storage.SecureStorage
import id.homebase.api.storage.SharedPreferences
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseKeyManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.di.allModules
import id.homebase.core.logging.CrashLogger
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.notifications.NotificationService
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext.startKoin

class MainApplication : Application(), KoinComponent {
    override fun onCreate() {
        super.onCreate()

        // Initialize storage (must be done before App() which may access storage)
        SecureStorage.initialize(this)
        SharedPreferences.initialize(this) // TODO: Maybe we should use injectable UserPreferences

        runBlocking {
            val dbKey = DatabaseKeyManager.getOrGenerateKey()
            // DatabaseManager.wipe { DatabaseDriverFactory(applicationContext).createDriver(dbKey)
            // } //
            // <-- Uncomment to wipe database
            DatabaseManager.initialize {
                DatabaseDriverFactory(applicationContext).createDriver(dbKey)
            }
        }

        startKoin {
            // Log Koin into Android logger
            androidLogger()
            // Reference Android context
            androidContext(this@MainApplication)
            // Load modules
            modules(allModules)
        }

        try {
            val logsDir = filesDir.resolve("logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            LoggerConfig.initialize(logDirectory =  Path(logsDir.absolutePath))
        } catch (e: Exception) {
            Logger.e("MainApplication", e, "Failed to initialize file logging")
        }

        // Set up uncaught exception handler for crash logging
        setupCrashHandler()

        // Initialize KMPNotifier for push notifications
        NotifierManager.initialize(
            configuration =
                NotificationPlatformConfiguration.Android(
                    notificationIconResId = R.mipmap.ic_launcher_foreground,
                    showPushNotification = true,
                )
        )

        // Register notification listener immediately so tokens/pushes arriving
        // before the UI composes are not lost
        val notificationService: NotificationService = get()
        notificationService.startListening()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                CrashLogger.logCrash(thread.name, throwable)
            } catch (e: Exception) {
                // If crash logging fails, still call the default handler
                e.printStackTrace()
            } finally {
                // Call the original handler to let the app crash normally
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
