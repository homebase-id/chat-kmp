package id.homebase.core

import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import id.homebase.photobase.backup.BackupBudget
import id.homebase.photobase.backup.BackupRunRequest
import id.homebase.photobase.backup.BackupTrigger
import id.homebase.core.photobase.PUSH_WAKE_BUDGET
import id.homebase.photobase.backup.PhotoBackupController
import id.homebase.photobase.backup.PhotoBackupEntry
import id.homebase.photobase.backup.backupProcessingTaskId
import id.homebase.photobase.backup.backupRefreshTaskId
import id.homebase.photobase.backup.cancelBackupTasks
import id.homebase.photobase.backup.submitBackupTasks
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
        task?.let { handle(it, BackupRunRequest(BackupTrigger.BackgroundRefresh, BackupBudget(PUSH_WAKE_BUDGET))) }
    }
    scheduler.registerForTaskWithIdentifier(backupProcessingTaskId, usingQueue = null) { task ->
        task?.let { handle(it, BackupRunRequest(BackupTrigger.BackgroundProcessing, BackupBudget(null))) }
    }
}

/** A relaunch for finished uploads may touch nothing else that would build the controller, which is what hears their results. */
fun startListeningForPhotoBackupUploads() {
    if (isDatabaseOpen()) KoinPlatformTools.defaultContext().get().get<PhotoBackupEntry>().onProcessStart()
}

private fun handle(task: BGTask, request: BackupRunRequest) {
    // First, so nothing below can end the chain.
    submitBackupTasks()
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
    // A task still pending when backup went off: take both requests back, so neither wakes the app again.
    if (!controller.settings.value.enabled) {
        cancelBackupTasks()
        task.setTaskCompletedWithSuccess(true)
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
        Logger.i(tag = "PhotoBackupTasks") { "${request.trigger} expired: iOS ended the task" }
        job.cancel()
        complete(false)
    }
}
