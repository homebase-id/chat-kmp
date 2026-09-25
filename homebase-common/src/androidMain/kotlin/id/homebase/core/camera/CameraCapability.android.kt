package id.homebase.core.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import co.touchlab.kermit.Logger

// Hardware-level walk derived from Signal-Android CameraXUtil.kt (AGPL-3.0, see NOTICE).
internal object CameraCapability {
    private const val TAG = "CameraCapability"

    @Volatile private var cachedAnyLegacy: Boolean? = null

    /** LEGACY HALs advertise Preview+Image+Video but deliver broken streams, so they bind per mode. */
    fun anyCameraIsLegacy(context: Context): Boolean =
        cachedAnyLegacy ?: queryAnyLegacy(context).also { cachedAnyLegacy = it }

    /** Null until [anyCameraIsLegacy] has queried the HAL once in this process. */
    val knownAnyCameraIsLegacy: Boolean? get() = cachedAnyLegacy

    // A lens's Preview+Image+Video support doesn't change within a process; simulating the bind costs ~60 ms per open.
    val simultaneousSupport: MutableMap<CameraLens, Boolean> = java.util.concurrent.ConcurrentHashMap()

    private fun queryAnyLegacy(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            manager.cameraIdList.any { id ->
                val level = try {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                } catch (_: NullPointerException) {
                    // Some Redmi HALs NPE inside getCameraCharacteristics; treat as the lowest level.
                    null
                }
                level == null || level == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            }
        } catch (e: CameraAccessException) {
            Logger.w(tag = TAG, throwable = e) { "Failed to enumerate cameras" }
            true
        }
    }
}
