@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.initWebSqlJs
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.App
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.AppConfig
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.appPermissions
import id.homebase.core.config.circleDriveTargetRequest
import id.homebase.core.config.targetDriveAccessRequest
import id.homebase.core.di.allModules
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

private fun bootStage(label: String, percent: Int): Unit =
    js("{ if (window.__homebaseBoot) window.__homebaseBoot(label, percent); }")

private fun bootDone(): Unit =
    js("{ if (window.__homebaseBootDone) window.__homebaseBootDone(); }")

@OptIn(ExperimentalComposeUiApi::class)
@ExperimentalBrowserHistoryApi
fun main() {
    // sql.js compiles its wasm asynchronously; finish that and build the in-memory database
    // before Koin (and the DriveSync graph behind the login screen) ever touches DatabaseManager.
    MainScope().launch {
        initWebSqlJs()
        bootStage("Preparing storage…", 60)
        DatabaseManager.initializeWithRecovery(DatabaseDriverFactory())
        bootStage("Opening database…", 75)
        startKoin { modules(allModules) }
        bootStage("Starting services…", 85)

        // Seamless owner-session login (issue #853). Two halves, both no-ops on the dev server:
        //  - Boot after the authorize redirect: the URL carries the callback params; feed them
        //    to handleCallback (which restores the persisted ECDH state) and boot the UI while
        //    the token exchange finishes.
        //  - Fresh unauthenticated boot on an identity origin: skip the login form, redirect
        //    the top window to the authorize endpoint, and let the owner cookie do the work.
        val youAuthFlowManager = GlobalContext.get().get<YouAuthFlowManager>()
        val callbackUrl = SeamlessOwnerLogin.consumePendingCallbackUrl()
        if (callbackUrl != null) {
            launch { youAuthFlowManager.handleCallback(callbackUrl) }
        } else if (SeamlessOwnerLogin.isEligible()) {
            // Wait for restoreSession() (launched in the manager's init) to decide whether
            // stored credentials exist — redirecting while already authenticated would be wrong.
            val authState = youAuthFlowManager.authState.first { it != YouAuthState.Initializing }
            if (authState == YouAuthState.Unauthenticated && SeamlessOwnerLogin.markAttemptedOnce()) {
                try {
                    val authorizeUrl = youAuthFlowManager.authorize(
                        identity = OdinId(SeamlessOwnerLogin.identityFromOrigin()),
                        appId = AppConfig.APP_ID,
                        appName = AppConfig.APP_NAME,
                        drives = targetDriveAccessRequest,
                        permissions = appPermissions,
                        circleDrives = circleDriveTargetRequest,
                        circles = listOf(CONFIRMED_CONNECTIONS_CIRCLE_ID, AUTO_CONNECTIONS_CIRCLE_ID),
                        persistForRedirect = true
                    )
                    bootStage("Signing you in…", 90)
                    SeamlessOwnerLogin.navigateToAuthorize(authorizeUrl)
                    return@launch // page is navigating away — don't boot the UI
                } catch (e: Exception) {
                    // Fall through to the normal login screen; the attempt flag prevents loops.
                    println("SeamlessOwnerLogin: seamless authorize failed — ${e.message}")
                    youAuthFlowManager.cancelAuth()
                }
            }
        }

        // Promote AuthConnectionCoordinator out of headless mode. Like desktop,
        // the web app has no FCM cold-wake — loading the page IS the foreground
        // signal — so without this the Authenticated branch defers connect()
        // forever and the app hangs on "syncing". Mirrors MainActivity.onCreate
        // (Android) and MainViewController() (iOS). Idempotent.
        GlobalContext.get().get<AuthConnectionCoordinator>().promoteToForeground()
        ComposeViewport("ComposeApp") {
            LaunchedEffect(Unit) { bootDone() }
            App(onNavHostReady = { it.bindToBrowserNavigation() })
        }
    }
}
