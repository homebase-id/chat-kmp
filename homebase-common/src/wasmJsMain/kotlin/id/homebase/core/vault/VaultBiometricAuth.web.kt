package id.homebase.core.vault

// Browsers have no platform biometric prompt; callers fall through to proceed
// when the result is Unavailable, which matches the desired UX.
actual suspend fun authenticateBiometric(title: String, subtitle: String): BiometricResult =
    BiometricResult.Unavailable
