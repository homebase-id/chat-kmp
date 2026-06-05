package id.homebase.app

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Graphics
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.w3c.dom.events.Event

/**
 * Re-keying the whole UI on this value disposes and re-composes the entire tree, forcing every
 * [androidx.compose.material3.Text] to re-shape and re-issue its glyph draws. That's the piece a
 * plain redraw can't do (a redraw reuses the existing text blobs, which still point at stale glyph
 * atlas slots). Bumped by [recoverTextRendering]. See [purgeSkiaGlyphCaches] for the why.
 */
private val recoveryEpoch = mutableStateOf(0)

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
            key(recoveryEpoch.value) {
                App(onNavHostReady = { it.bindToBrowserNavigation() })
            }
        }
        installTextRecoveryHook()
        scheduleGlyphAtlasRecovery()
    }
}

/**
 * THE SKIA FIX — isolated so it can be reused and mirrored on iOS (via TextRenderingHelper).
 *
 * Blank-text root cause: on cold start the first frames can rasterize text into the GPU glyph
 * atlas before the (WebGL on web / Metal on iOS) surface is fully ready — worse under the
 * production runtime, where backend/sync work contends with the first paint. The glyphs get
 * recorded as "resident" in the atlas but their pixels never land, so already-drawn text is blank
 * while freshly-drawn glyphs (e.g. characters you type) render fine. Frames survive because they
 * don't sample the atlas.
 *
 * [Graphics.purgeAllCaches] drops Skia's CPU strike cache **and** its GPU resource cache —
 * including the glyph-atlas pages. Freeing the atlas pages is the essential part: afterwards the
 * next draw finds the glyphs non-resident and re-rasterizes + re-uploads them to a clean atlas on
 * the now-ready surface. A purge alone isn't enough, though — the on-screen text blobs must also
 * be re-issued (see [recoverTextRendering]); a redraw of the existing blobs would just point back
 * at the freed slots.
 */
private fun purgeSkiaGlyphCaches() {
    Graphics.purgeAllCaches()
}

/**
 * Recover blank text: purge Skia's glyph/atlas caches, then force a full re-composition so every
 * Text re-issues its glyph draws against the clean atlas, and nudge a repaint.
 */
private fun recoverTextRendering() {
    purgeSkiaGlyphCaches()
    recoveryEpoch.value = recoveryEpoch.value + 1
    forceRepaint()
}

/**
 * Fire the recovery a couple of times as startup settles — the first attempt right after the
 * initial paint, a second once the backend connect/sync churn has died down (that contention is
 * what makes the first-frame race lose on the live deploy but not on a quiet localhost).
 */
private fun scheduleGlyphAtlasRecovery() {
    MainScope().launch {
        delay(1500)
        recoverTextRendering()
        delay(2500)
        recoverTextRendering()
    }
}

/**
 * Manual trigger kept for debugging: run
 * `window.dispatchEvent(new Event('homebase:recover-text'))` in the browser console to fire the
 * same recovery on demand.
 */
private fun installTextRecoveryHook() {
    val handler: (Event) -> Unit = {
        val before = Graphics.fontCacheUsed
        recoverTextRendering()
        consoleLog("[recover-text] purge + full re-composition (fontCacheUsed " + before + " -> " + Graphics.fontCacheUsed + ")")
    }
    window.addEventListener("homebase:recover-text", handler)
    consoleLog("[recover-text] hook ready — run: window.dispatchEvent(new Event('homebase:recover-text'))")
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun forceRepaint(): Unit = js("window.dispatchEvent(new Event('resize'))")

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun consoleLog(message: String): Unit = js("console.log(message)")
