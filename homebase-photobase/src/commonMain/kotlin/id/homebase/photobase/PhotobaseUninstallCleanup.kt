package id.homebase.photobase

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Uninstalling Photobase on the server tells the phone nothing, so the gallery's copy of the drive
 * and the backup ledger outlive it. Only a definite "not registered" wipes them, never a failed check.
 */
class PhotobaseUninstallCleanup(
    private val isInstalled: suspend () -> Boolean,
    private val forget: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    private var running: Job? = null

    /** On the app's scope: leaving the screen halfway must not leave the gallery wiped and the ledger not. */
    fun onNotInstalled() {
        if (running?.isActive == true) return
        running = scope.launch { forgetIfUninstalled() }
    }

    internal suspend fun forgetIfUninstalled() {
        val installed = try {
            isInstalled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "couldn't ask whether Photobase is installed; keeping the phone's copy" }
            return
        }
        if (installed) return
        forget()
        Logger.i(tag = TAG) { "Photobase isn't installed: forgot the gallery's copy and the backup ledger" }
    }

    private companion object {
        const val TAG = "PhotobaseUninstall"
    }
}
