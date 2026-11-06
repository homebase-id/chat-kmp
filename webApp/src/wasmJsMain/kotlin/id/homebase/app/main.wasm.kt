package id.homebase.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.initWebSqlJs
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.common.util.WindowTitle
import kotlinx.browser.document
import id.homebase.core.AppStartup
import id.homebase.core.App
import id.homebase.core.config.AppConfig
import id.homebase.core.config.appPermissions
import id.homebase.core.config.circleDriveTargetRequest
import id.homebase.core.config.targetDriveAccessRequest
import id.homebase.core.notifications.NotificationService
import id.homebase.core.ui.navigation.AppLink
import id.homebase.core.widget.ComposerAutocompleteProbe
import id.homebase.core.widget.initLazyStabilityFlags
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.configureWebResources
import org.koin.core.context.GlobalContext

private fun bootStage(label: String, percent: Int): Unit =
    js("{ if (window.__homebaseBoot) window.__homebaseBoot(label, percent); }")

private fun bootDone(): Unit =
    js("{ if (window.__homebaseBootDone) window.__homebaseBootDone(); }")

private fun recordAutocompleteTrace(trace: String): Unit =
    js("{ if (window.__homebaseErrors) window.__homebaseErrors.autocompleteTrace = trace; }")

private fun recordAutocompleteMismatch(report: String): Unit =
    js("{ if (window.__homebaseErrors) window.__homebaseErrors.autocompleteMismatch = report; }")

private fun recordAutocompleteComposing(composing: String): Unit =
    js("{ if (window.__homebaseErrors) window.__homebaseErrors.autocompleteComposing = composing; }")

private fun prefetchTable(url: String): Unit = js("{ fetch(url).catch(function (e) { console.warn('Resource table prefetch failed: ' + url, e); }); }")

@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class, ExperimentalFoundationApi::class)
@ExperimentalBrowserHistoryApi
fun main() {
    initLazyStabilityFlags()
    // Off by default on desktop and web; without it custom text context menu items are dropped.
    ComposeFoundationFlags.isNewContextMenuEnabled = true
    WebAppLink.captureFromUrl()
    configureWebResources {
        resourcePathMapping { path -> "./$path?v=$WEB_RESOURCES_VERSION" }
    }
    WEB_RESOURCE_TABLES.forEach { prefetchTable("./$it?v=$WEB_RESOURCES_VERSION") }
    ComposerAutocompleteProbe.install(object : ComposerAutocompleteProbe.Reporter {
        override fun onTrace(trace: String) = recordAutocompleteTrace(trace)
        override fun onMismatch(report: String) = recordAutocompleteMismatch(report)
        override fun onComposing(composing: String) = recordAutocompleteComposing(composing)
    })
    // sql.js compiles its wasm asynchronously: started first, it compiles while Koin builds.
    val sqlJs = MainScope().async(start = CoroutineStart.UNDISPATCHED) { initWebSqlJs() }
    AppStartup.startKoin()
    MainScope().launch {
        sqlJs.await()
        bootStage("Opening database…", 60)
        AppStartup.openDatabaseNow(DatabaseDriverFactory())
        AppStartup.afterDatabase()
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
                        appSlug = AppConfig.APP_SLUG,
                        drives = targetDriveAccessRequest,
                        permissions = appPermissions,
                        circleDrives = circleDriveTargetRequest,
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

        AppStartup.promoteToForeground()
        val notificationService = GlobalContext.get().get<NotificationService>()
        WebAppLink.consumePending()?.let { notificationService.navigateToApp(it.route) }
        val pageTitle = document.title
        ComposeViewport("ComposeApp") {
            LaunchedEffect(Unit) { bootDone() }
            LaunchedEffect(Unit) { WindowTitle.place.collect { document.title = WindowTitle.of(it, pageTitle) } }
            App(onNavHostReady = { nav ->
                WebAppLink.listenForTypedHash { notificationService.navigateToApp(it.route) }
                // A custom fragment turns off the library's own hash parsing; WebAppLink does that instead.
                nav.bindToBrowserNavigation { _ ->
                    AppLink.forBackStack(nav.currentBackStack.value)?.let { "#${it.slug}" }.orEmpty()
                }
            })
        }
    }
}
