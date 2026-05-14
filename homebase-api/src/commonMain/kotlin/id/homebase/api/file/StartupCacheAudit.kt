package id.homebase.api.file

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Logs a [CacheAudit] report once at app startup, so an
 * `adb logcat -s CacheAudit:*` capture shows the cache-directory breakdown
 * without needing the Storage Settings screen open.
 *
 * Wired as a `createdAtStart` Koin singleton (see `apiModule`) so it runs on
 * every platform with no per-platform code. The audit itself is dispatched onto
 * [Dispatchers.Default] — mirroring the fire-and-forget cleanup pattern in
 * `DriveFileProviderCached.init` — so it never blocks cold start or risks an ANR.
 *
 * Diagnostics only — see [CacheAudit].
 */
class StartupCacheAudit(fileOperationsProvider: FileOperationsProvider) {
    init {
        val cacheDir = fileOperationsProvider.getCacheDirectory()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                CacheAudit.logReport(CacheAudit.audit(cacheDir))
            }.onFailure {
                Logger.w(tag = "CacheAudit", throwable = it) { "startup cache audit failed" }
            }
        }
    }
}
