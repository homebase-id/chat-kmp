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
    fun appAndRuntimeEntitlementsKeepJvmLaunchAndCallKeys() {
        for (name in listOf("entitlements.plist", "runtime-entitlements.plist")) {
            val text = File("packaging/macos/$name").readText()
            for (key in required) {
                assertTrue("<key>$key</key>\\s*<true/>".toRegex().containsMatchIn(text), "$name missing $key")
            }
        }
    }
}
