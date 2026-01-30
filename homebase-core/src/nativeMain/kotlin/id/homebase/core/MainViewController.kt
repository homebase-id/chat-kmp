package id.homebase.core

import androidx.compose.ui.window.ComposeUIViewController
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    runBlocking {
        // DatabaseManager.wipe { DatabaseDriverFactory().createDriver() }
        DatabaseManager.initialize { DatabaseDriverFactory().createDriver() }
    }
    val controller = ComposeUIViewController { KoinApp() }
    MainViewControllerRef.instance = controller
    return controller
}

