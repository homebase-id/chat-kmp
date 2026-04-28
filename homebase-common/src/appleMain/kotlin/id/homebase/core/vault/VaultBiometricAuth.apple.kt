package id.homebase.core.vault

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
actual suspend fun authenticateBiometric(title: String, subtitle: String): BiometricResult {
    val context = LAContext()
    val canEvaluate = memScoped {
        context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
    }
    if (!canEvaluate) return BiometricResult.Unavailable

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            context.evaluatePolicy(
                LAPolicyDeviceOwnerAuthentication,
                localizedReason = subtitle.ifBlank { title }
            ) { success, _ ->
                if (cont.isActive) {
                    cont.resume(if (success) BiometricResult.Success else BiometricResult.Failure)
                }
            }
        }
    }
}
