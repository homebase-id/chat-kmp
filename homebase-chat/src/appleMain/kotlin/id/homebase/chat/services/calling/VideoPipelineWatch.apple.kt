package id.homebase.chat.services.calling

import WebRTC.RTCCallbackLogger
import WebRTC.RTCLoggingSeverity
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionDidStartRunningNotification
import platform.AVFoundation.AVCaptureSessionDidStopRunningNotification
import platform.AVFoundation.AVCaptureSessionErrorKey
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber

// AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground
private const val VIDEO_DEVICE_NOT_AVAILABLE_IN_BACKGROUND = 1L

// RTCLog and AVFoundation notices go to os_log only, which a device run cannot hand back with homebase.log.
@OptIn(ExperimentalForeignApi::class)
internal actual fun watchVideoPipeline(onCameraInterrupted: (interrupted: Boolean, detail: String) -> Unit): () -> Unit {
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
            val session = note?.`object` as? AVCaptureSession
            // Enabled the moment webrtc-kmp's private capturer starts its session, so the camera keeps running in PiP.
            if (name == AVCaptureSessionDidStartRunningNotification && session != null) {
                if (session.multitaskingCameraAccessSupported && !session.multitaskingCameraAccessEnabled) {
                    session.beginConfiguration()
                    session.multitaskingCameraAccessEnabled = true
                    session.commitConfiguration()
                }
                pinCaptureFormat(session)
                Logger.i(tag = "WebRtcKmp") {
                    "capture session multitaskingSupported=${session.multitaskingCameraAccessSupported} multitaskingEnabled=${session.multitaskingCameraAccessEnabled}"
                }
            }
            Logger.w(tag = "WebRtcKmp") {
                "capture session $name reason=${info?.get(AVCaptureSessionInterruptionReasonKey)} error=${info?.get(AVCaptureSessionErrorKey)}"
            }
            val reason = (info?.get(AVCaptureSessionInterruptionReasonKey) as? NSNumber)?.longValue
            val detail = if (reason == VIDEO_DEVICE_NOT_AVAILABLE_IN_BACKGROUND) "background" else "reason=$reason"
            when (name) {
                AVCaptureSessionWasInterruptedNotification -> onCameraInterrupted(true, detail)
                AVCaptureSessionInterruptionEndedNotification -> onCameraInterrupted(false, detail)
            }
        }
    }
    val native = RTCCallbackLogger().apply {
        severity = RTCLoggingSeverity.RTCLoggingSeverityWarning
        start { message -> Logger.w(tag = "WebRTC") { message.orEmpty().trimEnd() } }
    }
    return {
        native.stop()
        observers.forEach { center.removeObserver(it) }
    }
}
