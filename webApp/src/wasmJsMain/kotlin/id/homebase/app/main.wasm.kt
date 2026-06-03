package id.homebase.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.initWebSqlJs
import id.homebase.core.App
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.di.allModules
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

@OptIn(ExperimentalComposeUiApi::class)
@ExperimentalBrowserHistoryApi
fun main() {
    // sql.js compiles its wasm asynchronously; finish that and build the in-memory database
    // before Koin (and the DriveSync graph behind the login screen) ever touches DatabaseManager.
    MainScope().launch {
        initWebSqlJs()
        DatabaseManager.initializeWithRecovery(DatabaseDriverFactory())
        startKoin { modules(allModules) }
        // Promote AuthConnectionCoordinator out of headless mode. Like desktop,
        // the web app has no FCM cold-wake — loading the page IS the foreground
        // signal — so without this the Authenticated branch defers connect()
        // forever and the app hangs on "syncing". Mirrors MainActivity.onCreate
        // (Android) and MainViewController() (iOS). Idempotent.
        GlobalContext.get().get<AuthConnectionCoordinator>().promoteToForeground()
        ComposeViewport("ComposeApp") {
            App(onNavHostReady = { it.bindToBrowserNavigation() })
        }
    }
}
