package id.homebase.feed.crash

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Guards [GlobalCrashHandler.shouldLaunchRecoveryScreen] — the decision that used to be
 * a 10-second time window and silently swallowed the recovery screen for fast,
 * repeatable launch crashes (the user saw a bare death, no table-flip screen, no Share).
 */
class GlobalCrashHandlerScreenGuardTest {

    private val mainProcess = "id.homebase.feed.dev"
    private val crashProcess = "id.homebase.feed.dev:crash"
    private val report = "/data/.../crash/crash-123.txt"

    @Test
    fun mainProcessCrash_withReport_showsScreen() {
        assertTrue(GlobalCrashHandler.shouldLaunchRecoveryScreen(mainProcess, report))
    }

    @Test
    fun repeatedFastMainProcessCrash_stillShowsScreen() {
        // The regression: a deterministic launch crash crashes a few seconds into every
        // relaunch. Each one is a main-process crash, so each must surface the screen —
        // the old time guard suppressed every one after the first.
        repeat(5) {
            assertTrue(
                GlobalCrashHandler.shouldLaunchRecoveryScreen(mainProcess, report),
                "every main-process crash must show the recovery screen",
            )
        }
    }

    @Test
    fun crashProcessCrash_isSuppressed_toBreakTheLoop() {
        // CrashActivity itself crashed — relaunching it would loop forever.
        assertFalse(GlobalCrashHandler.shouldLaunchRecoveryScreen(crashProcess, report))
    }

    @Test
    fun unknownProcess_defaultsToShowing() {
        assertTrue(GlobalCrashHandler.shouldLaunchRecoveryScreen(null, report))
    }

    @Test
    fun noReport_showsNothing() {
        assertFalse(GlobalCrashHandler.shouldLaunchRecoveryScreen(mainProcess, null))
    }
}
