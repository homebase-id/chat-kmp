package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/**
 * How shared app code reaches backup. While [enabled] says no the controller isn't built, and a
 * failure in backup stops here, so it can't take startup, push handling or logout down with it.
 */
class PhotoBackupEntry(private val enabled: () -> Boolean, controller: () -> PhotoBackupController) {
    private val controller by lazy(controller)

    fun onProcessStart() = guarded { it.onProcessStart() }

    fun onAppForeground() = guarded { it.onAppForeground() }

    fun onAppBackground() = guarded { it.onAppBackground() }

    fun onPostAuthenticated() = guarded { it.onPostAuthenticated() }

    suspend fun onBeforeLogout() = guarded { it.onBeforeLogout() }

    suspend fun onWake(remaining: Duration) = guarded { it.onWake(remaining) }

    private inline fun guarded(call: (PhotoBackupController) -> Unit) {
        try {
            if (enabled()) call(controller)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(tag = "PhotoBackupEntry", throwable = e) { "backup call failed" }
        }
    }
}
