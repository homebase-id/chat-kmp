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
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.skia.Graphics
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.w3c.dom.events.Event

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
        installTextRecoveryHook()
    }
}

/**
 * INVESTIGATION HOOK (blank-text / stale glyph atlas) — see the iOS+web text-rendering notes.
 *
 * Symptom: already-drawn text is blank while freshly-drawn glyphs (e.g. characters you type)
 * render fine — the signature of a Skia GPU glyph atlas whose contents are gone but whose
 * residency bookkeeping still claims them present. A plain redraw (a window "resize") does NOT
 * recover it, because Skia keeps serving the dead atlas entries. The hypothesis: a *purge* of
 * Skia's caches is the missing step — it forces re-rasterization, after which a redraw re-uploads
 * the glyphs.
 *
 * This wires a manual trigger so we can confirm that on the live deploy without a UI. On the blank
 * screen, open the browser console and run:
 *
 *     window.dispatchEvent(new Event('homebase:recover-text'))
 *
 * If the text snaps back, purge-then-redraw is the fix and we generalize it (TextRenderingHelper +
 * iOS). If it does not, the atlas is dead at a deeper level than the global caches reach.
 */
private fun installTextRecoveryHook() {
    val handler: (Event) -> Unit = {
        val before = Graphics.fontCacheUsed
        Graphics.purgeAllCaches() // purgeFontCache + purgeResourceCache + all private Skia caches
        consoleLog("[recover-text] purged Skia caches (fontCacheUsed " + before + " -> " + Graphics.fontCacheUsed + "); forcing full redraw")
        // Skiko redraws the whole scene on a window resize; with the caches purged the glyphs are
        // re-rasterized and re-uploaded to a fresh atlas.
        window.dispatchEvent(Event("resize"))
    }
    window.addEventListener("homebase:recover-text", handler)
    consoleLog("[recover-text] hook ready — run: window.dispatchEvent(new Event('homebase:recover-text'))")
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun consoleLog(message: String): Unit = js("console.log(message)")
