package id.homebase.core

import chat_kmp.homebase_common.BuildConfig
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import id.homebase.photobase.backup.BackupBudget
import id.homebase.photobase.backup.BackupRunRequest
import id.homebase.photobase.backup.BackupTrigger
import id.homebase.photobase.backup.PhotoBackupController
import id.homebase.photobase.backup.PhotoBackupEntry
import id.homebase.photobase.backup.PhotoBackupRun
import id.homebase.photobase.backup.backupProcessingTaskId
import id.homebase.photobase.backup.backupRefreshTaskId
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler

private val scope = supervisedScope("photo-backup-tasks", ioDispatcher)

/** Swift checks this before starting any of backup's launch-time work. */
fun isPhotobaseEnabled(): Boolean = BuildConfig.PHOTOBASE_ENABLED

/** From `didFinishLaunching`, before it returns, whatever else the launch is doing. iOS kills an app that registers later. */
fun registerPhotoBackupBackgroundTasks() {
    val scheduler = BGTaskScheduler.sharedScheduler
    scheduler.registerForTaskWithIdentifier(backupRefreshTaskId, usingQueue = null) { task ->
        task?.let { handle(it, BackupRunRequest(BackupTrigger.BackgroundRefresh, BackupBudget(PhotoBackupRun.WAKE_BUDGET), findDeletions = false)) }
    }
    scheduler.registerForTaskWithIdentifier(backupProcessingTaskId, usingQueue = null) { task ->
        task?.let { handle(it, BackupRunRequest(BackupTrigger.BackgroundProcessing, BackupBudget(null), findDeletions = true)) }
    }
}

/** A relaunch for finished uploads may touch nothing else that would build the controller, which is what hears their results. */
fun startListeningForPhotoBackupUploads() {
    if (isDatabaseOpen()) KoinPlatformTools.defaultContext().get().get<PhotoBackupEntry>().onProcessStart()
}

private fun handle(task: BGTask, request: BackupRunRequest) {
    if (!isDatabaseOpen()) {
        task.setTaskCompletedWithSuccess(false)
        return
    }
    // Swift can't catch a Kotlin exception: one escaping this callback ends the app.
    val controller: PhotoBackupController = try {
        KoinPlatformTools.defaultContext().get().get<PhotoBackupController>().also { it.onProcessStart() }
    } catch (e: Exception) {
        Logger.e(tag = "PhotoBackupTasks", throwable = e) { "${request.trigger} could not start" }
        task.setTaskCompletedWithSuccess(false)
        return
    }
    val completed = atomic(false)
    fun complete(success: Boolean) {
        if (completed.compareAndSet(expect = false, update = true)) task.setTaskCompletedWithSuccess(success)
    }
    val job = scope.launch {
        var success = true
        try {
            controller.runFromBackground(request)
        } catch (e: CancellationException) {
            success = false
            throw e
        } catch (e: Exception) {
            Logger.w(tag = "PhotoBackupTasks", throwable = e) { "${request.trigger} failed" }
            success = false
        } finally {
            complete(success)
        }
    }
    task.expirationHandler = {
        job.cancel()
        complete(false)
    }
}
