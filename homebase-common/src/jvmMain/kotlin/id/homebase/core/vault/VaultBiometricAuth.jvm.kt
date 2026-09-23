package id.homebase.core.vault

import com.sun.jna.Platform

actual suspend fun authenticateBiometric(title: String, subtitle: String): BiometricResult = when {
    Platform.isMac() -> macAuthenticate(title, subtitle)
    Platform.isWindows() -> windowsAuthenticate(title, subtitle)
    Platform.isLinux() -> linuxAuthenticate(title, subtitle)
    else -> BiometricResult.Unavailable
}

actual fun isDeviceAuthAvailable(): Boolean = when {
    Platform.isMac() -> macAvailability()
    Platform.isWindows() -> windowsAvailability()
    Platform.isLinux() -> linuxAvailability()
    else -> false
}
