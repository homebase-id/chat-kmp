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
        ComposeViewport("ComposeApp") {
            key(recoveryEpoch.value) {
                App(onNavHostReady = { it.bindToBrowserNavigation() })
            }
        }
        installTextRecoveryHook()
        promoteToForegroundAfterFirstPaint()
    }
}

/**
 * THE FIX — prevent the blank-text race instead of trying to recover from it.
 *
 * The deployed bytes render fine on a quiet localhost and only blank under the live runtime, where
 * promoting AuthConnectionCoordinator out of headless mode immediately fires WebSocket connect +
 * drive sync. That background work contends with the very first paint, so glyphs get rasterized
 * into the GPU atlas before the WebGL surface is ready — recorded as resident but never drawn,
 * which a purge can't undo (the atlas lives in the per-context GPU cache). By letting the first
 * frames paint into a clean, ready surface *before* kicking off that work, the glyphs land
 * correctly the first time.
 *
 * promoteToForeground is still called (the Authenticated branch needs it or sync hangs on
 * "syncing") — just a few seconds later. Login is pre-auth, so this only delays sync start.
 * Same root cause as the intermittent iOS cold-start blank text (heavy startup work racing the
 * first Metal paint); the analogous fix there is to defer that work past the first frame.
 */
private fun promoteToForegroundAfterFirstPaint() {
    MainScope().launch {
        delay(3000)
        GlobalContext.get().get<AuthConnectionCoordinator>().promoteToForeground()
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
