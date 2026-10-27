package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacOsUnbundledDetectionTest {

    private fun unbundledOnMac(javaHome: String) = isMacOsRunningUnbundled("Mac OS X", javaHome)

    @Test
    fun installedAppWithSpaceInNameIsBundled() {
        assertFalse(unbundledOnMac("/Applications/Homebase Chat.app/Contents/runtime/Contents/Home"))
    }

    @Test
    fun devDistributableIsBundled() {
        assertFalse(
            unbundledOnMac("/repo/desktopApp/build/compose/binaries/main/app/homebase-chat.app/Contents/runtime/Contents/Home")
        )
    }

    @Test
    fun ideJbrIsUnbundled() {
        assertTrue(unbundledOnMac("/Applications/Android Studio.app/Contents/jbr/Contents/Home"))
    }

    @Test
    fun systemAndGradleJdksAreUnbundled() {
        assertTrue(unbundledOnMac("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"))
        assertTrue(unbundledOnMac("/Users/me/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x/jdk-21/Contents/Home"))
    }

    @Test
    fun nonMacIsNeverUnbundled() {
        assertFalse(isMacOsRunningUnbundled("Windows 11", "C:\\Program Files\\Java\\jdk-21"))
        assertFalse(isMacOsRunningUnbundled("Linux", "/usr/lib/jvm/java-21"))
    }
}
