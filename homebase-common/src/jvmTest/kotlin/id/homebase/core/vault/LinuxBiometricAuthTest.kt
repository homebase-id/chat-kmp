package id.homebase.core.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxBiometricAuthTest {

    @Test
    fun unregisteredActionIsDetectedFromPkcheckOutput() {
        assertTrue(isActionNotRegistered("Action $ACTION_ID is not registered"))
        assertTrue(
            isActionNotRegistered(
                "Error checking authorization: GDBus.Error:org.freedesktop.PolicyKit1.Error.Failed: " +
                    "Action $ACTION_ID is not registered",
            ),
        )
    }

    @Test
    fun registeredActionOutputIsNotFlagged() {
        assertFalse(isActionNotRegistered(""))
        assertFalse(isActionNotRegistered("polkit.temporary_authorization_id = ...\n"))
    }

    @Test
    fun exitCodeZeroIsSuccess() {
        assertEquals(BiometricResult.Success, mapPkcheckExitCode(0))
    }

    @Test
    fun notAuthorizedOrDismissedIsFailure() {
        assertEquals(BiometricResult.Failure, mapPkcheckExitCode(1))
        assertEquals(BiometricResult.Failure, mapPkcheckExitCode(3))
    }

    @Test
    fun noAgentOrUndocumentedCodesAreUnavailable() {
        listOf(2, 4, 126, 127, -1, 99).forEach {
            assertEquals(BiometricResult.Unavailable, mapPkcheckExitCode(it))
        }
    }

    @Test
    fun startTimeIsField22AfterTheParenthesizedComm() {
        val stat = "1234 (bash) S 1233 1234 1234 0 -1 4194304 100 0 0 0 1 2 0 0 20 0 1 0 99999 18446744073709551615"
        assertEquals(99999L, parseProcStatStartTime(stat))
    }

    @Test
    fun startTimeSurvivesACommContainingParensAndSpaces() {
        val stat = "1234 (weird)proc)) S 1233 1234 1234 0 -1 4194304 100 0 0 0 1 2 0 0 20 0 1 0 424242 0"
        assertEquals(424242L, parseProcStatStartTime(stat))
    }

    @Test
    fun startTimeIsNullWithoutAClosingParen() {
        assertNull(parseProcStatStartTime("not a stat line"))
    }

    @Test
    fun uidIsTheFirstFieldOfTheUidLine() {
        val status = "Name:\tbash\nPPid:\t1233\nUid:\t1000\t1000\t1000\t1000\nGid:\t1000\t1000\t1000\t1000\n"
        assertEquals(1000L, parseProcStatusUid(status))
    }

    @Test
    fun uidIsNullWithoutAUidLine() {
        assertNull(parseProcStatusUid("Name:\tbash\n"))
    }

    @Test
    fun processArgumentIncludesUidWhenKnown() {
        assertEquals("1234,99999,1000", pkcheckProcessArgument(1234, 99999, 1000))
    }

    @Test
    fun processArgumentOmitsUidWhenUnknown() {
        assertEquals("1234,99999", pkcheckProcessArgument(1234, 99999, null))
    }
}
