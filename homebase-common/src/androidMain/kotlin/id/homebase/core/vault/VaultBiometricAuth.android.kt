package id.homebase.core.vault

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import id.homebase.api.ActivityProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val DEVICE_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

private fun canAuthenticate(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(DEVICE_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

actual fun isDeviceAuthAvailable(): Boolean {
    val activity = ActivityProvider.getActivity() as? FragmentActivity ?: return false
    return canAuthenticate(activity)
}

actual suspend fun authenticateBiometric(title: String, subtitle: String): BiometricResult {
    val activity = ActivityProvider.getActivity() as? FragmentActivity
        ?: return BiometricResult.Unavailable

    if (!canAuthenticate(activity)) return BiometricResult.Unavailable

    return suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(BiometricResult.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(BiometricResult.Failure)
                }

                override fun onAuthenticationFailed() {
                    // onAuthenticationFailed fires on wrong fingerprint but the prompt
                    // stays open until the user succeeds or cancels, so we only resume
                    // from Succeeded/Error paths.
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(DEVICE_AUTHENTICATORS)
            .build()

        activity.runOnUiThread { prompt.authenticate(info) }
    }
}
