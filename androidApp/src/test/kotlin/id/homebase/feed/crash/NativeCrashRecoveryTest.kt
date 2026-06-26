package id.homebase.feed.crash

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Guards [NativeCrashRecovery.shouldShowNativeCrashRecovery] — the policy that decides, on
 * the next launch, whether the previous run died in a native (NDK signal) crash that the
 * JVM [GlobalCrashHandler] could not surface in-process, and therefore needs the recovery
 * screen shown now.
 *
 * The signal-catch itself (firebase-crashlytics-ndk) can only be exercised by forcing a
 * native crash on a device; this pins the classification logic, which is the part that
 * decides whether the screen appears.
 */
class NativeCrashRecoveryTest {

    @Test
    fun nativeCrash_previousRunCrashed_notSurfacedByJvm_showsRecovery() {
        // didCrashOnPreviousExecution=true, and GlobalCrashHandler did NOT mark it → native.
        assertTrue(NativeCrashRecovery.shouldShowNativeCrashRecovery(true, false))
    }

    @Test
    fun jvmCrash_alreadySurfacedInProcess_doesNotReshow() {
        // We showed CrashActivity in-process at JVM-crash time; don't double-show next launch.
        assertFalse(NativeCrashRecovery.shouldShowNativeCrashRecovery(true, true))
    }

    @Test
    fun cleanPreviousRun_showsNothing() {
        assertFalse(NativeCrashRecovery.shouldShowNativeCrashRecovery(false, false))
    }

    @Test
    fun cleanPreviousRun_evenWithStaleJvmMarker_showsNothing() {
        // No crash last run ⇒ never show, regardless of a leftover marker.
        assertFalse(NativeCrashRecovery.shouldShowNativeCrashRecovery(false, true))
    }
}
