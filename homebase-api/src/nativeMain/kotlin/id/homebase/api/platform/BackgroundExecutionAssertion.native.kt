package id.homebase.api.platform

import co.touchlab.kermit.Logger
import id.homebase.api.diagnostics.BgTrace
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual fun beginBackgroundExecutionAssertion(
    name: String,
    continuedProcessingTitle: String?,
): BackgroundExecutionAssertion {
    // Bridges until the system launches the continued task, and is the fallback if it never does.
    val uiKit = IosBackgroundExecutionAssertion(name)
    val continued =
        continuedProcessingTitle?.let { title ->
            ContinuedProcessingBridgeHolder.bridge?.begin(
                name = name,
                title = title,
                log = { Logger.i(tag = BgTrace.TAG) { it } },
                onStarted = { uiKit.end() },
            )
        } ?: return uiKit
    return object : BackgroundExecutionAssertion {
        override fun reportProgress(fraction: Float) = continued.reportProgress(fraction)

        override fun end() {
            uiKit.end()
            continued.end()
        }
    }
}

/**
 * `BGContinuedProcessingTask` (iOS 26+), implemented in Swift: Kotlin/Native links ObjC classes
 * strongly, so referencing it here would stop the app launching below iOS 26.
 */
interface ContinuedProcessingBridge {
    /** Null when the system won't run one; [log] says why. */
    fun begin(
        name: String,
        title: String,
        log: (String) -> Unit,
        onStarted: () -> Unit,
    ): ContinuedProcessingHandle?
}

interface ContinuedProcessingHandle {
    fun reportProgress(fraction: Float)

    fun end()
}

object ContinuedProcessingBridgeHolder {
    var bridge: ContinuedProcessingBridge? = null
}

private class IosBackgroundExecutionAssertion(private val name: String) :
    BackgroundExecutionAssertion {

    // UIApplication is main-thread-only, so both begin and end hop to the main queue —
    // which also serialises every access to taskId.
    private var taskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    init {
        dispatch_async(dispatch_get_main_queue()) {
            taskId = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) {
                // iOS kills the app if the window closes with the task still open.
                Logger.w(tag = BgTrace.TAG) { "assertion-expired name=$name" }
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
}
