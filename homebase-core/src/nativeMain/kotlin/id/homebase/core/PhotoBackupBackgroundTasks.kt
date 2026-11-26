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
    // Neither a closed database nor a controller that won't build can say backup is off, so both keep the chain alive.
    if (!isDatabaseOpen()) {
        submitBackupTasks()
        task.setTaskCompletedWithSuccess(false)
        return
    }
    // Swift can't catch a Kotlin exception: one escaping this callback ends the app.
    val controller: PhotoBackupController = try {
        KoinPlatformTools.defaultContext().get().get<PhotoBackupController>().also { it.onProcessStart() }
    } catch (e: Exception) {
        Logger.e(tag = "PhotoBackupTasks", throwable = e) { "${request.trigger} could not start" }
        submitBackupTasks()
        task.setTaskCompletedWithSuccess(false)
        return
    }
    // A task still pending when backup went off ends the chain here.
    if (!controller.settings.value.enabled) {
        task.setTaskCompletedWithSuccess(true)
        return
    }
    // Before the run, so nothing in it can end the chain.
    submitBackupTasks()
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
