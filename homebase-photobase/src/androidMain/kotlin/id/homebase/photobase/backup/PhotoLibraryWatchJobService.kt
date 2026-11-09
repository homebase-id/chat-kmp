package id.homebase.photobase.backup

import android.app.job.JobParameters
import android.app.job.JobService
import co.touchlab.kermit.Logger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** Fires when the photo library changes. Does no backing up itself, so it's never the same work as a run. */
class PhotoLibraryWatchJobService : JobService(), KoinComponent {
    override fun onStartJob(params: JobParameters): Boolean {
        try {
            val settings = get<PhotoBackupController>().settings.value
            if (settings.enabled) AndroidBackupTriggers(this).onLibraryChanged(settings)
        } catch (e: Exception) {
            Logger.e(tag = TAG, throwable = e) { "could not handle a library change" }
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    private companion object {
        const val TAG = "PhotoLibraryWatchJob"
    }
}
