package id.homebase.chat.services.calling

import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.position

actual fun platformCameras(): List<CameraInfo> =
    AVCaptureDevice.devicesWithMediaType(AVMediaTypeVideo).mapNotNull { entry ->
        val device = entry as? AVCaptureDevice ?: return@mapNotNull null
        when (device.position) {
            AVCaptureDevicePositionFront -> CameraInfo(device.uniqueID, CameraFacing.Front)
            AVCaptureDevicePositionBack -> CameraInfo(device.uniqueID, CameraFacing.Back)
            else -> null
        }
    }
