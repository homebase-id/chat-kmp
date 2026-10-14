package id.homebase.chat.services.calling

import id.homebase.api.ActivityProvider
import org.webrtc.Camera2Enumerator

actual fun platformCameras(): List<CameraInfo> {
    val enumerator = Camera2Enumerator(ActivityProvider.requireApplicationContext())
    return enumerator.deviceNames.mapNotNull { id ->
        when {
            enumerator.isFrontFacing(id) -> CameraInfo(id, CameraFacing.Front)
            enumerator.isBackFacing(id) -> CameraInfo(id, CameraFacing.Back)
            else -> null
        }
    }
}
