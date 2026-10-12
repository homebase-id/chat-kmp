package id.homebase.chat.services.calling

import android.content.Context
import org.koin.mp.KoinPlatformTools
import org.webrtc.Camera2Enumerator

actual fun platformCameras(): List<CameraInfo> {
    val enumerator = Camera2Enumerator(KoinPlatformTools.defaultContext().get().get<Context>())
    return enumerator.deviceNames.mapNotNull { id ->
        when {
            enumerator.isFrontFacing(id) -> CameraInfo(id, CameraFacing.Front)
            enumerator.isBackFacing(id) -> CameraInfo(id, CameraFacing.Back)
            else -> null
        }
    }
}
