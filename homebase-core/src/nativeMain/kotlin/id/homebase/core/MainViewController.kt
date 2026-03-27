package id.homebase.core

import androidx.compose.ui.window.ComposeUIViewController
import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseKeyManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.di.allModules
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.logging.setupIOSCrashHandler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.koin.core.context.startKoin
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

private var koinInitialized = false

/** Start Koin early so it's available before any Compose UI renders (e.g. on background push). */
fun initKoin() {
    if (koinInitialized) return
    koinInitialized = true
    startKoin { modules(allModules) }
}

fun MainViewController(): UIViewController {
    initKoin()
    // Initialize file logging first
    try {
        val logDir = getLogDirectory()
        LoggerConfig.initialize(logDirectory = logDir)
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = "MainViewController") { "Failed to initialize file logging" }
    }

    // Set up crash handler
    setupIOSCrashHandler()

    runBlocking {
        val dbKey = DatabaseKeyManager.getOrGenerateKey()
        // DatabaseManager.wipe { DatabaseDriverFactory().createDriver(dbKey) }
        DatabaseManager.initialize { DatabaseDriverFactory().createDriver(dbKey) }
    }
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
