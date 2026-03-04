package id.homebase.core

import androidx.compose.ui.window.ComposeUIViewController
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseKeyManager
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    runBlocking {
        val dbKey = DatabaseKeyManager.getOrGenerateKey()
        // DatabaseManager.wipe { DatabaseDriverFactory().createDriver(dbKey) }
        DatabaseManager.initialize { DatabaseDriverFactory().createDriver(dbKey) }
    }
    val controller = ComposeUIViewController { KoinApp() }
    MainViewControllerRef.instance = controller
    return controller
}
