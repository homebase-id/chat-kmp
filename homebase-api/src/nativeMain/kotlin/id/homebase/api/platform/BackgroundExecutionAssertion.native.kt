package id.homebase.api.platform

import co.touchlab.kermit.Logger
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual fun beginBackgroundExecutionAssertion(name: String): BackgroundExecutionAssertion =
    IosBackgroundExecutionAssertion(name)

private class IosBackgroundExecutionAssertion(private val name: String) :
    BackgroundExecutionAssertion {

    // UIApplication is main-thread-only, so both begin and end hop to the main queue —
    // which also serialises every access to taskId.
    private var taskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    init {
        dispatch_async(dispatch_get_main_queue()) {
            taskId = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) {
                // iOS kills the app if the window closes with the task still open.
                Logger.w(tag = TAG) { "$name: assertion expired before end()" }
                endOnMain()
            }
        }
    }

    override fun end() {
        dispatch_async(dispatch_get_main_queue()) { endOnMain() }
    }

    private fun endOnMain() {
        val id = taskId
        if (id == UIBackgroundTaskInvalid) return
        taskId = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(id)
    }

    private companion object {
        const val TAG = "BackgroundAssertion"
    }
}
