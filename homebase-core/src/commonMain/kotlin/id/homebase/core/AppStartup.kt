package id.homebase.core

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.crash.CrashReporting
import id.homebase.core.location.tracking.LocationTrackingCoordinator
import id.homebase.core.util.PlatformInfo
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

    val isDatabaseOpen: Boolean get() = database.isOpen

    fun startKoin(platform: KoinAppDeclaration = {}) {
        if (koinStarted) return
        koinStarted = true
        koinStart {
            platform()
            modules(allModules)
        }
    }

    /** False while the database can't be read yet: the platform tries again when that may have changed. */
    suspend fun openDatabase(factory: DatabaseDriverFactory): Boolean = database.tryOpen(opener(factory))

    /** For a platform whose database is readable from the start: if it isn't, the cause is thrown as it is. */
    suspend fun openDatabaseNow(factory: DatabaseDriverFactory) = database.open(opener(factory))

    fun whenDatabaseOpen(block: () -> Unit) = database.whenOpen(block)

    /**
     * What every process start runs once the database is open, a background wake (push, location, boot) as much as a
     * launch; [platform] adds its own, e.g. its call coordinator.
     */
    fun afterDatabase(platform: () -> Unit = {}) = whenDatabaseOpen {
        val koin = KoinPlatformTools.defaultContext().get()
        // Wakes never reach onPostAuthenticated, so tracking is re-armed here; the coordinator's preferences need the database.
        if (koin.get<PlatformInfo>().supportsBackgroundWake) koin.get<LocationTrackingCoordinator>().onProcessStart()
        platform()
        CrashReporting.markStartupComplete()
    }

    // The process starting is the foreground signal on desktop and web; iOS sends it when its UI appears.
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
