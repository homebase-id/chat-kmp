package id.homebase.core.vault

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
actual suspend fun authenticateBiometric(title: String, subtitle: String): BiometricResult {
    val context = LAContext()
    val biometricPolicy = LAPolicyDeviceOwnerAuthenticationWithBiometrics
    val canUseBiometrics = memScoped {
        context.canEvaluatePolicy(biometricPolicy, error = null)
    }
    val policy = if (canUseBiometrics) {
        biometricPolicy
    } else {
        val canUsePasscode = memScoped {
            context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
        }
        if (!canUsePasscode) return BiometricResult.Unavailable
        LAPolicyDeviceOwnerAuthentication
    }

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            context.evaluatePolicy(
                policy,
                localizedReason = subtitle.ifBlank { title }
            ) { success, _ ->
                if (cont.isActive) {
                    cont.resume(if (success) BiometricResult.Success else BiometricResult.Failure)
                }
            }
        }
    }
}
