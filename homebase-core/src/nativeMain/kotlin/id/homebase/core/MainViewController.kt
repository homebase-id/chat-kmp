package id.homebase.core

import androidx.compose.ui.window.ComposeUIViewController
import chat_kmp.homebase_common.BuildConfig
import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseKeyManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.di.allModules
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.logging.StartupLogger
import id.homebase.core.logging.setErrorCollectionEnabled
import id.homebase.core.logging.setupIOSCrashHandler
import id.homebase.core.settings.UserPreferencesHelper
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.koin.core.context.startKoin
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

private var koinInitialized = false
private var appInitialized = false

/** Start Koin early so it's available before any Compose UI renders (e.g. on background push). */
fun initKoin() {
    if (koinInitialized) return
    koinInitialized = true
    startKoin { modules(allModules) }
}

/**
 * Run all heavy initialization (Koin, logging, crash reporting, database) outside
 * [MainViewController] so the main-thread run-loop is free to process CoreText / Metal
 * callbacks before the first Compose frame is rendered.
 *
 * Call from `AppDelegate.didFinishLaunchingWithOptions` — idempotent.
 */
@OptIn(ExperimentalForeignApi::class)
fun initializeApp() {
    if (appInitialized) return
    appInitialized = true

    initKoin()

    // Initialize file logging first
    try {
        val logDir = getLogDirectory()
        LoggerConfig.initialize(logDirectory = logDir)

        val versionName = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "Unknown"
        val versionCode = (NSBundle.mainBundle.infoDictionary?.get("CFBundleVersion") as? String)?.toIntOrNull() ?: 0
        StartupLogger.logAppStartupInfo(versionName, versionCode, BuildConfig.APP_BUILD_TIME)
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = "MainViewController") { "Failed to initialize file logging" }
    }

    // Configure Crashlytics based on user preference
    // Note: Koin must be initialized before accessing UserPreferences
    setErrorCollectionEnabled(UserPreferencesHelper.errorCollectionEnabled)

    // Set up crash handler
    setupIOSCrashHandler()

    runBlocking {
        val dbKey = DatabaseKeyManager.getOrGenerateKey()
        try {
            DatabaseManager.initialize { DatabaseDriverFactory().createDriver(dbKey) }
        } catch (e: Exception) {
            Logger.e("MainViewController", e, "Database init failed, resetting")
            // Delete the corrupted/undecryptable database file
            val fileManager = NSFileManager.defaultManager
            val dbDir = "${NSHomeDirectory()}/databases"
            fileManager.removeItemAtPath("$dbDir/odin-2.db", null)
            fileManager.removeItemAtPath("$dbDir/odin-2.db-journal", null)
            fileManager.removeItemAtPath("$dbDir/odin-2.db-wal", null)
            fileManager.removeItemAtPath("$dbDir/odin-2.db-shm", null)

            // Clear the stale encryption key and generate a fresh one
            DatabaseKeyManager.clearKey()
            val freshKey = DatabaseKeyManager.getOrGenerateKey()

            DatabaseManager.initialize { DatabaseDriverFactory().createDriver(freshKey) }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController {
    initializeApp()
    val controller = ComposeUIViewController { App() }
    MainViewControllerRef.instance = controller
    return controller
}

@OptIn(ExperimentalForeignApi::class)
private fun getLogDirectory(): Path {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null
    )?.path ?: ""

    val logsPath = "$documentDirectory/logs"
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(logsPath)) {
        fileManager.createDirectoryAtPath(
            path = logsPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }
    return Path(logsPath)
}
