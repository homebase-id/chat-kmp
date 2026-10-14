package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceFormat
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVFrameRateRange
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreMedia.CMFormatDescriptionGetMediaSubType
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionGetDimensions
import platform.Foundation.NSError
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_time

// webrtc-kmp 0.125.11's selectFormat reads format sizes through a freed CMVideoDimensions (copyContents), lands on 4K
// and runs the sensor there all call; this re-picks after every session start, which also covers a camera flip.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun pinCaptureFormat(session: AVCaptureSession) {
    val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)
    dispatch_async(queue) {
        val device = session.inputs.filterIsInstance<AVCaptureDeviceInput>()
            .map { it.device }
            .firstOrNull { it.hasMediaType(AVMediaTypeVideo) } ?: return@dispatch_async
        val byDescription = device.formats.filterIsInstance<AVCaptureDeviceFormat>().associateBy { it.describe() }
        val chosenDescription = chooseCaptureFormat(byDescription.keys.toList())
        val was = device.activeFormat.describe()
        if (chosenDescription == null) {
            Logger.w("WebRtcKmp") { "capture format none usable, keeping ${was.label()}" }
            return@dispatch_async
        }
        val chosen = byDescription.getValue(chosenDescription)
        val failed = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!device.lockForConfiguration(error.ptr)) return@memScoped error.value?.localizedDescription ?: "unknown"
            device.activeFormat = chosen
            device.activeVideoMinFrameDuration = CMTimeMake(1, SendVideoCap.FPS)
            device.activeVideoMaxFrameDuration = CMTimeMake(1, SendVideoCap.FPS)
            device.unlockForConfiguration()
            null
        }
        if (failed != null) {
            Logger.w("WebRtcKmp") { "capture format lock failed $failed, keeping ${was.label()}" }
            return@dispatch_async
        }
        Logger.i("WebRtcKmp") { "capture format chosen ${chosenDescription.label(SendVideoCap.FPS)} (was ${was.label()})" }
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC.toLong()), queue) {
            Logger.i("WebRtcKmp") { "capture format after 1s ${device.activeFormat.describe().label()}" }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun AVCaptureDeviceFormat.describe(): CaptureFormat {
    val (width, height) = CMVideoFormatDescriptionGetDimensions(formatDescription).useContents { width to height }
    val maxFps = videoSupportedFrameRateRanges.filterIsInstance<AVFrameRateRange>()
        .filter { it.minFrameRate <= SendVideoCap.FPS }
        .maxOfOrNull { it.maxFrameRate } ?: 0.0
    val subType = CMFormatDescriptionGetMediaSubType(formatDescription)
    val fourCc = (24 downTo 0 step 8).map { ((subType shr it) and 0xFFu).toInt().toChar() }.joinToString("")
    return CaptureFormat(width, height, maxFps, fourCc, videoBinned)
}

private fun CaptureFormat.label(fps: Int? = null) =
    "${width}x$height${fps?.let { "@$it" }.orEmpty()} $pixelFormat max${maxFps.toInt()}fps${if (binned) " binned" else ""}"
