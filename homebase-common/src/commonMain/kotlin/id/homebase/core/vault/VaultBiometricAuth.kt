package id.homebase.core.vault

sealed interface BiometricResult {
    data object Success : BiometricResult
    data object Failure : BiometricResult
    data object Unavailable : BiometricResult
}

/**
 * Prompt the user for biometric authentication.
 * Returns [BiometricResult.Success] on confirmed identity,
 * [BiometricResult.Failure] on explicit cancellation or failure,
 * [BiometricResult.Unavailable] when the platform has no biometric hardware
 * configured — in which case the caller should proceed without the gate.
 */
expect suspend fun authenticateBiometric(title: String, subtitle: String): BiometricResult
