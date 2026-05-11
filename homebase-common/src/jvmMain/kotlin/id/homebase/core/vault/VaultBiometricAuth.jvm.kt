package id.homebase.core.vault

actual suspend fun authenticateBiometric(title: String, subtitle: String): BiometricResult =
    BiometricResult.Unavailable
