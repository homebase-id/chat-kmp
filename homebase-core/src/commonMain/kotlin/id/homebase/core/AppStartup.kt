package id.homebase.core

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.crash.CrashReporting
import id.homebase.core.location.tracking.LocationTrackingCoordinator
import id.homebase.core.logging.StartupLogger
import id.homebase.core.notifications.NotificationService
import id.homebase.core.util.PlatformInfo
import chat_kmp.homebase_common.BuildConfig
import id.homebase.core.di.allModules
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatformTools
import kotlin.time.TimeSource
import org.koin.core.context.startKoin as koinStart

// Koin before the database on every platform: iOS can launch before the first unlock, so nothing built at Koin start
// may touch it (AppStartupKoinTest). Main thread only.
object AppStartup {
    private var koinStarted = false
    private val database = DatabaseStartGate()
    private var started = false
    private val afterStart = mutableListOf<() -> Unit>()

    val isDatabaseOpen: Boolean get() = database.isOpen

    fun startKoin(platform: KoinAppDeclaration = {}) {
        if (koinStarted) return
        koinStarted = true
        koinStart {
            platform()
            modules(allModules)
        }
        val info = KoinPlatformTools.defaultContext().get().get<PlatformInfo>()
        StartupLogger.logAppStartupInfo(info.versionName, info.versionCode, BuildConfig.APP_BUILD_TIME)
    }

    /** False while the database can't be read yet: the platform tries again when that may have changed. */
    suspend fun openDatabase(factory: DatabaseDriverFactory): Boolean = database.tryOpen(opener(factory))

    /** For a platform whose database is readable from the start: if it isn't, the cause is thrown as it is. */
    suspend fun openDatabaseNow(factory: DatabaseDriverFactory) = database.open(opener(factory))

    fun whenDatabaseOpen(block: () -> Unit) = database.whenOpen(block)

    /** Every process start, background wakes included, runs this once the database is open; [platform] adds its own. */
    fun afterDatabase(platform: () -> Unit = {}) = whenDatabaseOpen {
        val koin = KoinPlatformTools.defaultContext().get()
        // First and synchronous: an iOS location relaunch needs its CLLocationManager back before the run loop drains.
        // Only where the OS wakes the app for location; elsewhere it would build the location graph for nothing.
        if (koin.get<PlatformInfo>().supportsBackgroundWake) koin.get<LocationTrackingCoordinator>().onProcessStart()
        // On a start that opens the database, pushes and tokens find a listener before any UI.
        koin.get<NotificationService>().startListening()
        platform()
        CrashReporting.markStartupComplete()
        started = true
        afterStart.toList().also { afterStart.clear() }.forEach { it() }
    }

    /** Runs [block] once [afterDatabase] has run (at once if it has): a deferred user action shouldn't beat startup. */
    fun whenStarted(block: () -> Unit) {
        if (started) block() else afterStart += block
    }

    // Ends headless mode where the OS starts processes without UI (Android, iOS); elsewhere it only builds AuthCC.
    fun promoteToForeground() = whenDatabaseOpen {
        KoinPlatformTools.defaultContext().get().get<AuthConnectionCoordinator>().promoteToForeground()
    }

    private fun opener(factory: DatabaseDriverFactory): suspend () -> Unit {
        check(koinStarted) { "AppStartup.startKoin first" }
        return {
            val mark = TimeSource.Monotonic.markNow()
            DatabaseManager.initializeWithRecovery(factory)
            Logger.i(tag = "AppStartup") { "database open: ${mark.elapsedNow().inWholeMilliseconds}ms" }
        }
    }
}
