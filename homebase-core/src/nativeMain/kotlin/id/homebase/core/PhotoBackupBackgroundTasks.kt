package id.homebase.core

import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import id.homebase.photobase.backup.BackupBudget
import id.homebase.photobase.backup.BackupRunRequest
import id.homebase.photobase.backup.BackupTrigger
import id.homebase.photobase.backup.PhotoBackupController
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
    if (isDatabaseOpen()) KoinPlatformTools.defaultContext().get().get<PhotoBackupController>()
}

private fun handle(task: BGTask, request: BackupRunRequest) {
    if (!isDatabaseOpen()) {
        task.setTaskCompletedWithSuccess(false)
        return
    }
    val controller: PhotoBackupController = KoinPlatformTools.defaultContext().get().get()
    controller.onProcessStart()
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
