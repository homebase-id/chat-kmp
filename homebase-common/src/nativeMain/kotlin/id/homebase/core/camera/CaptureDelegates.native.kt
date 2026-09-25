package id.homebase.core.camera

import platform.AVFoundation.AVCaptureFileOutput
import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVErrorRecordingSuccessfullyFinishedKey
import platform.AVFoundation.fileDataRepresentation
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.darwin.NSObject

internal class PhotoCaptureDelegate(
    private val onFinished: (PhotoCaptureDelegate, NSData?, NSError?) -> Unit,
) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
    override fun captureOutput(output: AVCapturePhotoOutput, didFinishProcessingPhoto: AVCapturePhoto, error: NSError?) {
        onFinished(this, if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null, error)
    }
}

internal class RecordingDelegate(
    private val onStarted: () -> Unit,
    private val onFinished: (url: NSURL, usable: Boolean, error: NSError?) -> Unit,
) : NSObject(), AVCaptureFileOutputRecordingDelegateProtocol {
    override fun captureOutput(
        output: AVCaptureFileOutput,
        didStartRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
    ) = onStarted()

    override fun captureOutput(
        output: AVCaptureFileOutput,
        didFinishRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
        error: NSError?,
    ) {
        // Interruptions and disk-full still finalize a playable movie; AVFoundation flags that in userInfo.
        val finishedAnyway = (error?.userInfo?.get(AVErrorRecordingSuccessfullyFinishedKey) as? NSNumber)?.boolValue == true
        onFinished(didFinishRecordingToOutputFileAtURL, error == null || finishedAnyway, error)
    }
}
