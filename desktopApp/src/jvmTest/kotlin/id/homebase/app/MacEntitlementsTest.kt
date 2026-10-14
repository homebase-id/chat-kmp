package id.homebase.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class MacEntitlementsTest {
    private val required = listOf(
        "com.apple.security.device.camera",
        "com.apple.security.device.audio-input",
        "com.apple.security.cs.disable-library-validation",
        "com.apple.security.cs.allow-jit",
        "com.apple.security.cs.allow-unsigned-executable-memory",
    )

    @Test
    fun entitlementsKeepJvmLaunchAndCallKeys() {
        val text = File("packaging/macos/entitlements.plist").readText()
        for (key in required) {
            assertTrue("<key>$key</key>\\s*<true/>".toRegex().containsMatchIn(text), "missing $key")
        }
    }

    @Test
    fun conveyorReleaseRequestsCameraAndMicrophone() {
        val conf = File("conveyor_common.conf").readText()
        for (key in required.take(2)) {
            assertTrue("\"$key\"\\s*=\\s*true".toRegex().containsMatchIn(conf), "conveyor missing $key")
        }
    }
}
