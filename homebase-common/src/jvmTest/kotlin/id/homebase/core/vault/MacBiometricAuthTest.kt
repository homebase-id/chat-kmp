package id.homebase.core.vault

import com.sun.jna.Platform
import kotlin.test.Test

class MacBiometricAuthTest {

    @Test
    fun availabilityProbesWithoutPrompting() {
        if (!Platform.isMac()) return
        macAvailability()
    }
}
