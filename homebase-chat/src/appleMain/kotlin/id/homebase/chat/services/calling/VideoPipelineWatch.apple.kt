package id.homebase.chat.services.calling

import WebRTC.RTCCallbackLogger
import WebRTC.RTCLoggingSeverity
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureSessionDidStartRunningNotification
import platform.AVFoundation.AVCaptureSessionDidStopRunningNotification
import platform.AVFoundation.AVCaptureSessionErrorKey
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.Foundation.NSNotificationCenter

// RTCLog and AVFoundation notices go to os_log only, which a device run cannot hand back with homebase.log.
@OptIn(ExperimentalForeignApi::class)
internal actual fun watchVideoPipeline(): () -> Unit {
    val center = NSNotificationCenter.defaultCenter
    val observers = listOf(
        AVCaptureSessionDidStartRunningNotification,
        AVCaptureSessionDidStopRunningNotification,
        AVCaptureSessionWasInterruptedNotification,
        AVCaptureSessionInterruptionEndedNotification,
        AVCaptureSessionRuntimeErrorNotification,
    ).map { name ->
        center.addObserverForName(name, `object` = null, queue = null) { note ->
            val info = note?.userInfo
            Logger.w("WebRtcKmp") {
                "capture session $name reason=${info?.get(AVCaptureSessionInterruptionReasonKey)} error=${info?.get(AVCaptureSessionErrorKey)}"
            }
        }
    }
    val native = RTCCallbackLogger().apply {
        severity = RTCLoggingSeverity.RTCLoggingSeverityWarning
        start { message -> Logger.w("WebRTC") { message.orEmpty().trimEnd() } }
    }
    return {
        native.stop()
        observers.forEach { center.removeObserver(it) }
    }
}
