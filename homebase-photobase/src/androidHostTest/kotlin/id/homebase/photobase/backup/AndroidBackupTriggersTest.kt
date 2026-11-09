package id.homebase.photobase.backup

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBackupTriggersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = BackupSettings(enabled = true)
    private val triggers = AndroidBackupTriggers(context)
    private val jobs get() = context.getSystemService(JobScheduler::class.java)
    private val watch get() = jobs.getPendingJob(AndroidBackupTriggers.LIBRARY_WATCH_JOB_ID)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun rearmingWatchesTheWholeLibraryFromItsOwnJob() {
        triggers.rearm(settings)

        val job = watch!!
        assertEquals(4, job.triggerContentUris!!.size)
        assertEquals(PhotoLibraryWatchJobService::class.java.name, job.service.className)
    }

    @Test
    fun rearmingLeavesAPendingWatchAlone() {
        triggers.rearm(settings)
        val first = watch

        triggers.rearm(settings)

        assertSame(first, watch)
    }

    @Test
    fun aFiredWatchArmsItselfAgain() {
        triggers.rearm(settings)
        jobs.cancel(AndroidBackupTriggers.LIBRARY_WATCH_JOB_ID)

        triggers.onLibraryChanged(settings)

        assertNotNull(watch)
    }

    @Test
    fun aLibraryChangeStartsARunUnderTheUsersConstraints() {
        triggers.onLibraryChanged(settings.copy(wifiOnly = true, chargingOnly = true))

        val run = WorkManager.getInstance(context).getWorkInfosForUniqueWork("photobase-media-changed").get().single()
        assertEquals(NetworkType.UNMETERED, run.constraints.requiredNetworkType)
        assertTrue(run.constraints.requiresCharging())
    }

    @Test
    fun turningBackupOffStopsTheWatch() {
        triggers.rearm(settings)

        triggers.rearm(settings.copy(enabled = false))

        assertNull(watch)
    }
}
