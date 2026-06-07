/*
 * iOS-only — despite the platform-neutral filename.
 *
 * Lives in `homebase-core/src/nativeMain/` because iOS's app shell is Swift
 * (`iosApp/`) rather than its own Kotlin module — there's no `iosApp` Kotlin
 * counterpart to Android's `androidApp/` Gradle module, so the Kotlin entry
 * point Swift calls into has to live in this multiplatform module's iOS
 * source set. The `nativeMain` source set in this project is wired to
 * iOS targets only (per CLAUDE.md "KMP Source Set Convention").
 *
 * Swift entry: `iosApp/iosApp/ContentView.swift` ->
 * `MainViewControllerKt.MainViewController()` (the wrapper class name comes
 * from this file's name, so renaming the file requires updating Swift).
 */
package id.homebase.core

import androidx.compose.ui.window.ComposeUIViewController
import chat_kmp.homebase_common.BuildConfig
import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.di.allModules
import id.homebase.core.diagnostics.MainThreadWatchdog
import id.homebase.core.diagnostics.installGpuTextDiagnostics
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

    val startMark = kotlin.time.TimeSource.Monotonic.markNow()
    Logger.i(tag = "TextRendering") { "initializeApp() started" }

    initKoin()
    Logger.i(tag = "TextRendering") { "Koin init: ${startMark.elapsedNow().inWholeMilliseconds}ms" }

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

    // Detect main-thread stalls and log them to homebase.log. On iOS the stack itself can't be
    // captured from a background thread, but the "stalled for Nms" breadcrumb is still recorded.
    MainThreadWatchdog().start()

    // Field instrumentation for the intermittent iOS blank-text bug (stale GPU glyph atlas). Logs a
    // ColdStart snapshot now and observes foreground-after-idle + memory warnings — the moments it
    // strikes — so a user-reported recurrence can be diagnosed from homebase.log. See
    // GpuTextDiagnostics / BLANK_TEXT_INVESTIGATION.md.
    installGpuTextDiagnostics()

    val dbMark = kotlin.time.TimeSource.Monotonic.markNow()
    runBlocking {
        DatabaseManager.initializeWithRecovery(DatabaseDriverFactory())
    }
    Logger.i(tag = "TextRendering") { "DB init (runBlocking): ${dbMark.elapsedNow().inWholeMilliseconds}ms" }
    Logger.i(tag = "TextRendering") { "initializeApp() total: ${startMark.elapsedNow().inWholeMilliseconds}ms" }
}

@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController {
    initializeApp()
    // Promote AuthCC out of headless mode — this is the iOS analogue of
    // Android's MainActivity.onCreate: it fires only when SwiftUI actually
    // presents the UI (ContentView.body builds ComposeView, which calls
    // makeUIViewController). FCM background-fetch wake-ups don't reach
    // here, so headless work stays headless. Idempotent. See
    // AuthConnectionCoordinator.promoteToForeground.
    val authCC: id.homebase.core.auth.AuthConnectionCoordinator =
        org.koin.mp.KoinPlatformTools.defaultContext().get().get()
    authCC.promoteToForeground()
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
